package mysql

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	mysqldriver "github.com/go-sql-driver/mysql"
	"wechat-observatory/internal/bridge"
)

func (s *Store) ensureFileTables(ctx context.Context) error {
	statements := []string{
		`ALTER TABLE bridge_module_outbox ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'text'`,
		`ALTER TABLE bridge_module_outbox ADD COLUMN attachment_id CHAR(32) NULL`,
		`ALTER TABLE bridge_module_outbox ADD COLUMN file_lease_token VARCHAR(64) NULL`,
		`ALTER TABLE bridge_module_outbox ADD COLUMN file_instance_id VARCHAR(64) NULL`,
		`ALTER TABLE bridge_module_outbox ADD COLUMN file_account_session VARCHAR(64) NULL`,
		`ALTER TABLE bridge_module_outbox ADD COLUMN native_started_at DATETIME(6) NULL`,
		`CREATE TABLE IF NOT EXISTS bridge_file_attachments (
		 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
		 outbox_id BIGINT NULL UNIQUE, device VARCHAR(128) NOT NULL, owner_wxid VARCHAR(191) NOT NULL,
		 idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
		 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
		 name VARCHAR(240) NOT NULL, size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL,
		 expires_at DATETIME(6) NOT NULL, bytes_removed BOOLEAN NOT NULL DEFAULT FALSE,
		 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
		 UNIQUE KEY uq_file_idempotency(device,owner_wxid,idempotency_hash),
		 KEY idx_file_cleanup(bytes_removed,expires_at)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`,
	}
	for _, statement := range statements {
		if _, err := s.executor(ctx).ExecContext(ctx, statement); err != nil {
			var mysqlErr *mysqldriver.MySQLError
			if !errors.As(err, &mysqlErr) || mysqlErr.Number != 1060 {
				return err
			}
		}
	}
	return nil
}

func (s *Store) EnqueueFile(ctx context.Context, req bridge.FileEnqueue) (item bridge.ModuleOutboxItem, replay bool, err error) {
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	var oldID sql.NullInt64
	var fingerprint string
	err = tx.QueryRowContext(ctx, `SELECT outbox_id,fingerprint FROM bridge_file_attachments
	 WHERE device=? AND owner_wxid=? AND idempotency_hash=? FOR UPDATE`, req.Device, req.OwnerWxID, req.IdempotencyHash).Scan(&oldID, &fingerprint)
	if err == nil {
		if fingerprint != req.Fingerprint {
			return item, false, bridge.ErrIdempotencyConflict
		}
		if !oldID.Valid {
			return item, false, bridge.ErrFileUnavailable
		}
		if err = tx.Commit(); err != nil {
			return
		}
		return bridge.ModuleOutboxItem{ID: oldID.Int64, Kind: "file"}, true, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return
	}
	err = nil
	if !req.AllowNew {
		return item, false, bridge.ErrAccountSession
	}
	if !req.Stored {
		return item, false, bridge.ErrFileQuota
	}
	expires, err := time.Parse(time.RFC3339, req.Attachment.ExpiresAt)
	if err != nil {
		return item, false, err
	}
	result, err := tx.ExecContext(ctx, `INSERT INTO bridge_module_outbox
	 (device,owner_wxid,wxid,text,status,kind,attachment_id) VALUES(?,?,?,'','pending','file',?)`, req.Device, req.OwnerWxID, req.WxID, req.Attachment.ID)
	if err != nil {
		return
	}
	id, err := result.LastInsertId()
	if err != nil {
		return
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO bridge_file_attachments
	 (id,outbox_id,device,owner_wxid,idempotency_hash,fingerprint,name,size,sha256,expires_at)
	 VALUES(?,?,?,?,?,?,?,?,?,?)`, req.Attachment.ID, id, req.Device, req.OwnerWxID, req.IdempotencyHash, req.Fingerprint, req.Attachment.Name, req.Attachment.Size, req.Attachment.SHA256, expires)
	if err != nil {
		return
	}
	if err = tx.Commit(); err != nil {
		return
	}
	item, err = s.findOutboxItem(ctx, id)
	return
}

// Enrich after the original cursor is closed: account locks may intentionally
// use a single SQL connection, so a nested read on an open cursor is unsafe.
func (s *Store) addFileMetadata(ctx context.Context, items []bridge.ModuleOutboxItem) error {
	if len(items) == 0 {
		return nil
	}
	args := make([]any, len(items))
	places := make([]string, len(items))
	index := map[int64]int{}
	for i := range items {
		args[i] = items[i].ID
		places[i] = "?"
		index[items[i].ID] = i
	}
	rows, err := s.executor(ctx).QueryContext(ctx, `SELECT o.id,o.kind,o.file_lease_token,o.native_started_at,
	 a.id,a.name,a.size,a.sha256,a.expires_at FROM bridge_module_outbox o
	 LEFT JOIN bridge_file_attachments a ON a.id=o.attachment_id WHERE o.id IN (`+strings.Join(places, ",")+`)`, args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var kind string
		var token, attachmentID, name, hash sql.NullString
		var size sql.NullInt64
		var started, expires sql.NullTime
		if err = rows.Scan(&id, &kind, &token, &started, &attachmentID, &name, &size, &hash, &expires); err != nil {
			return err
		}
		item := &items[index[id]]
		item.Kind = kind
		item.LeaseToken = token.String
		if started.Valid {
			item.NativeStartedAt = formatTime(started.Time)
		}
		if attachmentID.Valid {
			item.Attachment = &bridge.FileAttachment{ID: attachmentID.String, Name: name.String, Size: size.Int64, SHA256: hash.String, ExpiresAt: expires.Time.UTC().Format(time.RFC3339)}
		}
	}
	return rows.Err()
}

func fileLeaseToken() (string, error) {
	var token [32]byte
	_, err := rand.Read(token[:])
	return hex.EncodeToString(token[:]), err
}

// Revocation does not take the account named lock. Hold the key row before the
// file row so a native grant or result cannot commit across a completed revoke.
func lockFileCredential(ctx context.Context, tx *sql.Tx, apiKey, device, credentialID string, authVersion int64) error {
	var credential string
	var version int64
	var enabled bool
	err := tx.QueryRowContext(ctx, `SELECT credential_id,auth_version,enabled FROM bridge_api_keys
	 WHERE code=? AND device=? FOR UPDATE`, apiKey, device).Scan(&credential, &version, &enabled)
	if errors.Is(err, sql.ErrNoRows) {
		return bridge.ErrAccountSession
	}
	if err != nil {
		return err
	}
	if !enabled || credentialID == "" || credential != credentialID || version != authVersion {
		return bridge.ErrAccountSession
	}
	return nil
}

func (s *Store) OperateFile(ctx context.Context, req bridge.FileOperation) (result bridge.FileTaskResult, err error) {
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	if err = lockFileCredential(ctx, tx, req.APIKey, req.Device, req.CredentialID, req.AuthVersion); err != nil {
		return
	}
	var device, owner, instance, session, token, status, hash string
	var lease, started sql.NullTime
	var expires time.Time
	var current time.Time
	var removed bool
	err = tx.QueryRowContext(ctx, `SELECT o.device,o.owner_wxid,COALESCE(o.file_instance_id,''),COALESCE(o.file_account_session,''),
	 COALESCE(o.file_lease_token,''),o.status,o.lease_until,o.native_started_at,a.sha256,a.expires_at,a.bytes_removed,CURRENT_TIMESTAMP(6)
	 FROM bridge_module_outbox o JOIN bridge_file_attachments a ON a.id=o.attachment_id
	 WHERE o.id=? AND o.kind='file' FOR UPDATE`, req.ID).Scan(&device, &owner, &instance, &session, &token, &status, &lease, &started, &hash, &expires, &removed, &current)
	if errors.Is(err, sql.ErrNoRows) {
		return result, bridge.ErrFileConflict
	}
	if err != nil {
		return
	}
	if device != req.Device || owner != req.WxID || req.InstanceID == "" || instance != req.InstanceID {
		return result, bridge.ErrFileConflict
	}
	result.LeaseExpired = status == "leased" && !started.Valid && (!lease.Valid || !lease.Time.After(current))
	if req.Operation != "status" {
		if req.LeaseToken == "" || token != req.LeaseToken {
			return result, bridge.ErrFileConflict
		}
		if req.Operation == "begin" && started.Valid { // Lost first grant is never permission to submit again.
		} else {
			if status != "leased" || session != req.AccountSession || !lease.Valid || !lease.Time.After(current) || !expires.After(current) || removed {
				return result, bridge.ErrFileConflict
			}
			switch req.Operation {
			case "download":
			case "renew":
				_, err = tx.ExecContext(ctx, `UPDATE bridge_module_outbox SET lease_until=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 60 SECOND) WHERE id=?`, req.ID)
			case "begin":
				if req.SHA256 != hash {
					return result, bridge.ErrFileConflict
				}
				_, err = tx.ExecContext(ctx, `UPDATE bridge_module_outbox SET status='native_started',native_started_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE id=?`, req.ID)
				result.Granted = err == nil
			default:
				return result, bridge.ErrInvalidFile
			}
			if err != nil {
				return
			}
		}
	}
	if err = tx.Commit(); err != nil {
		return
	}
	result.Item, err = s.findOutboxItem(ctx, req.ID)
	result.OK = err == nil
	return
}

// File ACKs are installation/token bound. A current new session of the same
// installation may reconcile a durable native-started task, never start it again.
func (s *Store) ackFile(ctx context.Context, req bridge.ModuleAckRequest, ack bridge.ModuleAckItem) (accepted bool, replay bool, err error) {
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	if err = lockFileCredential(ctx, tx, req.APIKey, req.Device, req.CredentialID, req.AuthVersion); err != nil {
		return
	}
	var device, owner, instance, session, token, status, lastError string
	var record sql.NullInt64
	var started, lease sql.NullTime
	var now time.Time
	err = tx.QueryRowContext(ctx, `SELECT device,owner_wxid,COALESCE(file_instance_id,''),COALESCE(file_account_session,''),
	 COALESCE(file_lease_token,''),status,COALESCE(last_error,''),chat_record_id,native_started_at,lease_until,CURRENT_TIMESTAMP(6)
	 FROM bridge_module_outbox WHERE id=? AND kind='file' FOR UPDATE`, ack.ID).Scan(&device, &owner, &instance, &session, &token, &status, &lastError, &record, &started, &lease, &now)
	if errors.Is(err, sql.ErrNoRows) {
		return false, false, nil
	}
	if err != nil {
		return
	}
	if req.InstanceID == "" || device != req.Device || owner != req.WxID || instance != req.InstanceID || ack.LeaseToken == "" || token != ack.LeaseToken {
		return false, false, nil
	}
	if status == ack.Status && (status == "sent" || status == "failed" || status == "unknown") && record.Int64 == ack.ChatRecordID && lastError == ack.Error {
		return true, true, tx.Commit()
	}
	canReconcile := started.Valid && (status == "native_started" || status == "unknown")
	canFail := status == "leased" && ack.Status == "failed" && session == req.AccountSession && lease.Valid && lease.Time.After(now)
	if !canReconcile && !canFail {
		return false, false, nil
	}
	if ack.Status != "sent" && ack.Status != "failed" && ack.Status != "unknown" {
		return false, false, nil
	}
	if ack.Status == "sent" && ack.ChatRecordID <= 0 || record.Int64 > 0 && record.Int64 != ack.ChatRecordID {
		return false, false, nil
	}
	_, err = tx.ExecContext(ctx, `UPDATE bridge_module_outbox SET status=?,last_error=?,chat_record_id=COALESCE(?,chat_record_id),lease_until=NULL WHERE id=?`, ack.Status, nullString(ack.Error), nullInt64(ack.ChatRecordID), ack.ID)
	if err != nil {
		return
	}
	return true, false, tx.Commit()
}

func (s *Store) FileCleanupCandidates(ctx context.Context) (ids []string, err error) {
	// This row-level CAS serializes with begin, even though the sweeper does not
	// hold an account lock. A granted native handoff can never become cancelled.
	_, err = s.executor(ctx).ExecContext(ctx, `UPDATE bridge_module_outbox o JOIN bridge_file_attachments a ON a.id=o.attachment_id
	 SET o.status='cancelled',o.last_error='file expired before native submission',o.lease_until=NULL
	 WHERE o.kind='file' AND o.status IN ('pending','leased') AND o.native_started_at IS NULL AND a.expires_at<=CURRENT_TIMESTAMP(6)`)
	if err != nil {
		return
	}
	rows, err := s.executor(ctx).QueryContext(ctx, `SELECT a.id FROM bridge_file_attachments a LEFT JOIN bridge_module_outbox o ON o.id=a.outbox_id
	 WHERE a.bytes_removed=FALSE AND (o.id IS NULL OR o.status IN ('sent','failed','cancelled')) LIMIT 1000`)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
func (s *Store) MarkFileRemoved(ctx context.Context, id string) error {
	_, err := s.executor(ctx).ExecContext(ctx, `UPDATE bridge_file_attachments SET bytes_removed=TRUE WHERE id=?`, id)
	return err
}
func (s *Store) FileBlobReferenced(ctx context.Context, id string) (bool, error) {
	var found bool
	err := s.executor(ctx).QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM bridge_file_attachments WHERE id=?)`, id).Scan(&found)
	return found, err
}
