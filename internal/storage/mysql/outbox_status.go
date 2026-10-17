package mysql

import (
	"context"
	"database/sql"
	"time"

	"wechat-observatory/internal/bridge"
)

func (s *Store) ListOutboxStatuses(ctx context.Context, filter bridge.OutboxStatusFilter) ([]bridge.OutboxStatusItem, error) {
	filter, err := filter.Normalize()
	if err != nil {
		return nil, err
	}
	query := `SELECT id, device, owner_wxid, wxid, text, chat_record_id, status,
		attempt_count, last_error, created_at, updated_at, lease_until
		FROM bridge_module_outbox WHERE device=? AND owner_wxid=?`
	args := []any{filter.Device, filter.OwnerWxID}
	if filter.WxID != "" {
		query += " AND wxid=?"
		args = append(args, filter.WxID)
	}
	switch filter.Status {
	case "active":
		query += " AND status IN ('pending','leased') ORDER BY id ASC"
	case "failed":
		query += " AND status='failed' ORDER BY id DESC"
	default:
		query += " ORDER BY id DESC"
	}
	query += " LIMIT ?"
	args = append(args, filter.Limit)
	rows, err := s.executor(ctx).QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []bridge.OutboxStatusItem{}
	for rows.Next() {
		var item bridge.OutboxStatusItem
		var owner, lastError sql.NullString
		var recordID sql.NullInt64
		var created, updated time.Time
		var lease sql.NullTime
		if err := rows.Scan(&item.ID, &item.Device, &owner, &item.WxID, &item.Text, &recordID,
			&item.Status, &item.AttemptCount, &lastError, &created, &updated, &lease); err != nil {
			return nil, err
		}
		item.OwnerWxID = owner.String
		item.ChatRecordID = recordID.Int64
		item.LastError = lastError.String
		item.CreatedAt = formatTime(created)
		item.UpdatedAt = formatTime(updated)
		if lease.Valid {
			item.LeaseUntil = formatTime(lease.Time)
		}
		items = append(items, item)
	}
	return items, rows.Err()
}
