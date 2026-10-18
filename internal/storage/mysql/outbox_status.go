package mysql

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"wechat-observatory/internal/bridge"
)

func (s *Store) OutboxStatusByID(ctx context.Context, id int64, device, owner string) (item bridge.OutboxStatusItem, found bool, err error) {
	var lease sql.NullTime
	err = s.executor(ctx).QueryRowContext(ctx, `SELECT lease_until FROM bridge_module_outbox WHERE id=? AND device=? AND owner_wxid=?`, id, device, owner).Scan(&lease)
	if errors.Is(err, sql.ErrNoRows) {
		return item, false, nil
	}
	if err != nil {
		return
	}
	item.ModuleOutboxItem, err = s.findOutboxItem(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return item, false, nil
	}
	if err != nil {
		return
	}
	if lease.Valid {
		item.LeaseUntil = formatTime(lease.Time)
	}
	return item, true, nil
}

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
		query += " AND status IN ('pending','leased','native_started','unknown') ORDER BY id ASC"
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
	if err = rows.Err(); err != nil {
		return nil, err
	}
	if err = rows.Close(); err != nil {
		return nil, err
	}
	base := make([]bridge.ModuleOutboxItem, len(items))
	for i := range items {
		base[i] = items[i].ModuleOutboxItem
	}
	if err = s.addFileMetadata(ctx, base); err != nil {
		return nil, err
	}
	for i := range items {
		items[i].ModuleOutboxItem = base[i]
	}
	return items, nil
}
