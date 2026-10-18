package mysql

import (
	"context"
	"database/sql"
	"errors"

	"wechat-observatory/internal/bridge"
)

// No foreign key: a lightweight idempotency tombstone must outlive the outbox
// history, otherwise a lost response retried later could send a second message.
const textIdempotencyTable = `CREATE TABLE IF NOT EXISTS bridge_text_idempotency (
 device VARCHAR(128) NOT NULL, owner_wxid VARCHAR(191) NOT NULL,
 key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 outbox_id BIGINT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(device,owner_wxid,key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`

func (s *Store) LookupTextIdempotency(ctx context.Context, req bridge.IdempotentTextEnqueue) (id int64, found bool, err error) {
	var fingerprint string
	err = s.executor(ctx).QueryRowContext(ctx, `SELECT outbox_id,fingerprint FROM bridge_text_idempotency
	 WHERE device=? AND owner_wxid=? AND key_hash=?`, req.Device, req.OwnerWxID, req.KeyHash).Scan(&id, &fingerprint)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return
	}
	if fingerprint != req.Fingerprint {
		return 0, false, bridge.ErrIdempotencyConflict
	}
	return id, true, nil
}

func (s *Store) EnqueueIdempotentText(ctx context.Context, req bridge.IdempotentTextEnqueue) (id int64, replay bool, err error) {
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	// INSERT acquires the unique-key lock before allocating any outbox ID. A
	// competing replica waits for the first whole transaction, then replays it.
	_, err = tx.ExecContext(ctx, `INSERT INTO bridge_text_idempotency(device,owner_wxid,key_hash,fingerprint)
	 VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE key_hash=key_hash`, req.Device, req.OwnerWxID, req.KeyHash, req.Fingerprint)
	if err != nil {
		return
	}
	var existing sql.NullInt64
	var fingerprint string
	err = tx.QueryRowContext(ctx, `SELECT outbox_id,fingerprint FROM bridge_text_idempotency
	 WHERE device=? AND owner_wxid=? AND key_hash=? FOR UPDATE`, req.Device, req.OwnerWxID, req.KeyHash).Scan(&existing, &fingerprint)
	if err != nil {
		return
	}
	if fingerprint != req.Fingerprint {
		return 0, false, bridge.ErrIdempotencyConflict
	}
	if existing.Valid {
		id, replay = existing.Int64, true
		err = tx.Commit()
		return
	}
	result, err := tx.ExecContext(ctx, `INSERT INTO bridge_module_outbox(device,owner_wxid,wxid,text,status)
	 VALUES(?,?,?,?,'pending')`, req.Device, req.OwnerWxID, req.WxID, req.Text)
	if err != nil {
		return
	}
	id, err = result.LastInsertId()
	if err != nil {
		return
	}
	_, err = tx.ExecContext(ctx, `UPDATE bridge_text_idempotency SET outbox_id=? WHERE device=? AND owner_wxid=? AND key_hash=?`, id, req.Device, req.OwnerWxID, req.KeyHash)
	if err != nil {
		return
	}
	err = tx.Commit()
	return
}
