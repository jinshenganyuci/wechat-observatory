package mysql

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"wechat-observatory/internal/bridge"
)

const fileTaskViewColumns = `o.id,o.device,o.owner_wxid,o.wxid,o.status,o.attempt_count,
 o.chat_record_id,o.last_error,o.created_at,o.updated_at,o.lease_until,o.native_started_at,
 a.id,a.name,a.size,a.sha256,a.expires_at,a.bytes_removed,
 a.cleanup_requested_at,a.cleanup_updated_at,a.cleanup_last_error,CURRENT_TIMESTAMP(6)`
const fileTaskViewJoin = ` FROM bridge_module_outbox o JOIN bridge_file_attachments a ON a.id=o.attachment_id `

func scanFileTaskView(row interface{ Scan(...any) error }) (v bridge.FileTaskView, err error) {
	var record sql.NullInt64
	var lastError, cleanupError sql.NullString
	var lease, started, requested, cleaned sql.NullTime
	var created, updated, expires, now time.Time
	var removed bool
	err = row.Scan(&v.ID, &v.Device, &v.OwnerWxID, &v.WxID, &v.Status, &v.AttemptCount, &record, &lastError,
		&created, &updated, &lease, &started, &v.Attachment.ID, &v.Attachment.Name, &v.Attachment.Size, &v.Attachment.SHA256,
		&expires, &removed, &requested, &cleaned, &cleanupError, &now)
	if err != nil {
		return
	}
	v.ChatRecordID, v.LastError = record.Int64, lastError.String
	v.CreatedAt, v.UpdatedAt = formatTime(created), formatTime(updated)
	v.Attachment.ExpiresAt = expires.UTC().Format(time.RFC3339)
	if lease.Valid {
		v.LeaseUntil = formatTime(lease.Time)
	}
	if started.Valid {
		v.NativeStartedAt = formatTime(started.Time)
	}
	v.ServerCopy.State = "retained"
	if requested.Valid {
		v.ServerCopy.CleanupRequestedAt = formatTime(requested.Time)
		v.ServerCopy.State = "cleanup_pending"
	}
	if removed {
		v.ServerCopy.State = "removed"
	}
	if cleaned.Valid {
		v.ServerCopy.UpdatedAt = formatTime(cleaned.Time)
	}
	v.ServerCopy.LastError = cleanupError.String
	v.SetCleanupPermissions(now)
	return
}

func (s *Store) ListFileTasks(ctx context.Context, filter bridge.FileTaskFilter) ([]bridge.FileTaskView, error) {
	filter, err := filter.Normalize()
	if err != nil {
		return nil, err
	}
	query := "SELECT " + fileTaskViewColumns + fileTaskViewJoin + " WHERE o.kind='file' AND o.device=? AND o.owner_wxid=?"
	args := []any{filter.Device, filter.OwnerWxID}
	if filter.BeforeID > 0 {
		query += " AND o.id<?"
		args = append(args, filter.BeforeID)
	}
	switch filter.Status {
	case "retained":
		query += " AND a.bytes_removed=FALSE"
	case "unconfirmed":
		query += " AND o.status IN ('unknown','native_started')"
	}
	query += " ORDER BY o.id DESC LIMIT ?"
	args = append(args, filter.Limit+1)
	rows, err := s.executor(ctx).QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	views := []bridge.FileTaskView{}
	for rows.Next() {
		v, e := scanFileTaskView(rows)
		if e != nil {
			return nil, e
		}
		views = append(views, v)
	}
	return views, rows.Err()
}
func (s *Store) FileTaskViewByID(ctx context.Context, id int64, device, owner string) (v bridge.FileTaskView, found bool, err error) {
	v, err = scanFileTaskView(s.executor(ctx).QueryRowContext(ctx, "SELECT "+fileTaskViewColumns+fileTaskViewJoin+" WHERE o.kind='file' AND o.id=? AND o.device=? AND o.owner_wxid=?", id, device, owner))
	if errors.Is(err, sql.ErrNoRows) {
		return v, false, nil
	}
	return v, err == nil, err
}
func (s *Store) RequestFileCleanup(ctx context.Context, req bridge.FileCleanupRequest) (v bridge.FileTaskView, err error) {
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	// The same outbox/attachment row lock used by begin and renew makes the
	// permission check atomic. The request never clears their token or grant.
	v, err = scanFileTaskView(tx.QueryRowContext(ctx, "SELECT "+fileTaskViewColumns+fileTaskViewJoin+" WHERE o.kind='file' AND o.id=? AND o.device=? AND o.owner_wxid=? FOR UPDATE", req.ID, req.Device, req.OwnerWxID))
	if errors.Is(err, sql.ErrNoRows) {
		return v, bridge.ErrFileTaskNotFound
	}
	if err != nil {
		return
	}
	if v.ServerCopy.State == "removed" || v.ServerCopy.State == "cleanup_pending" {
		err = tx.Commit()
		return
	}
	if req.Mode == "cancel_unstarted" && v.CanCancelUnstarted {
		_, err = tx.ExecContext(ctx, `UPDATE bridge_module_outbox SET status='cancelled',
		 last_error='cancelled by administrator before native submission',lease_until=NULL WHERE id=?`, req.ID)
		if err != nil {
			return
		}
	} else if req.Mode != "server_only" || !v.CanCleanupServer {
		return v, bridge.ErrFileConflict
	}
	_, err = tx.ExecContext(ctx, `UPDATE bridge_file_attachments SET cleanup_requested_at=CURRENT_TIMESTAMP(6) WHERE id=?`, v.Attachment.ID)
	if err != nil {
		return
	}
	if err = tx.Commit(); err != nil {
		return
	}
	v, _, err = s.FileTaskViewByID(ctx, req.ID, req.Device, req.OwnerWxID)
	return
}
func (s *Store) RecordFileCleanupFailure(ctx context.Context, id, code string) error {
	_, err := s.executor(ctx).ExecContext(ctx, `UPDATE bridge_file_attachments
	 SET cleanup_requested_at=COALESCE(cleanup_requested_at,CURRENT_TIMESTAMP(6)),cleanup_updated_at=CURRENT_TIMESTAMP(6),cleanup_last_error=?
	 WHERE id=? AND bytes_removed=FALSE`, code, id)
	return err
}
