package mysql

import (
	"context"
	"errors"
	"time"

	"wechat-observatory/internal/bridge"
)

const activeInstallationPresenceStatement = `INSERT INTO bridge_module_installations
	(device, instance_id, owner_wxid, last_state, last_seen_at, last_active_at)
	VALUES (?, ?, ?, 'active', ?, ?)
	ON DUPLICATE KEY UPDATE
		last_state = 'active',
		last_seen_at = GREATEST(last_seen_at, VALUES(last_seen_at)),
		last_active_at = GREATEST(COALESCE(last_active_at, VALUES(last_active_at)), VALUES(last_active_at))`

const activeRuntimePresenceStatement = `INSERT INTO bridge_module_runtime
	(device, wxid, api_key, updated_at) VALUES (?, ?, ?, ?)
	ON DUPLICATE KEY UPDATE
		wxid = VALUES(wxid), api_key = VALUES(api_key),
		updated_at = GREATEST(updated_at, VALUES(updated_at))`

// RecordActiveClientPresence never uses the database processing time. A queued
// frame's receive time updates both views together without moving either back.
func (s *Store) RecordActiveClientPresence(ctx context.Context, binding bridge.AccountBinding, apiKey string, receivedAt time.Time) error {
	held, ok := ctx.Value(accountConnKey{}).(accountConnection)
	if !ok || held.store != s || held.device != binding.Device || receivedAt.IsZero() {
		return bridge.ErrAccountSession
	}
	tx, err := s.executor(ctx).BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	current, found, err := scanAccountBinding(tx.QueryRowContext(ctx,
		"SELECT "+accountBindingColumns+" FROM bridge_module_account_current WHERE device=? FOR UPDATE", binding.Device))
	if err != nil {
		return err
	}
	if !found || !binding.SameSession(current) {
		return bridge.ErrAccountSession
	}
	var credential string
	var version int64
	var enabled bool
	if err := tx.QueryRowContext(ctx, `SELECT credential_id, auth_version, enabled FROM bridge_api_keys
		WHERE code=? AND device=? FOR UPDATE`, apiKey, binding.Device).Scan(&credential, &version, &enabled); err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return err
		}
		return bridge.ErrAccountSession
	}
	if !enabled || credential != binding.CredentialID || version != binding.AuthVersion {
		return bridge.ErrAccountSession
	}
	if _, err := tx.ExecContext(ctx, activeInstallationPresenceStatement,
		binding.Device, binding.InstanceID, binding.OwnerWxID, receivedAt, receivedAt); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, activeRuntimePresenceStatement,
		binding.Device, binding.OwnerWxID, apiKey, receivedAt); err != nil {
		return err
	}
	return tx.Commit()
}
