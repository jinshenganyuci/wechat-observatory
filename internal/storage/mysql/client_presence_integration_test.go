package mysql

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"wechat-observatory/internal/bridge"
)

func TestClientPresenceMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skip("set " + offlineOutboxIntegrationDSNEnv + " to a disposable test database")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	store, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	store.db.SetMaxOpenConns(1) // Nested presence writes must reuse the account lock's connection.
	var database string
	if err := store.db.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&database); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.ToLower(database), "test") {
		t.Fatal("integration requires a test database")
	}
	if err := store.ApplyMigrations(ctx); err != nil {
		t.Fatal(err)
	}
	device := fmt.Sprintf("presence_%d", time.Now().UnixNano())
	key := device + "_key"
	if _, err := store.db.ExecContext(ctx, "INSERT INTO bridge_api_keys (code,credential_id,device) VALUES (?,?,?)", key, key, device); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanup, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		for _, table := range []string{"bridge_module_account_history", "bridge_module_account_current", "bridge_module_installations",
			"bridge_module_switch_requests", "bridge_module_outbox", "bridge_module_runtime", "bridge_device_session_lease", "bridge_api_keys"} {
			if _, err := store.db.ExecContext(cleanup, "DELETE FROM "+table+" WHERE device=?", device); err != nil {
				t.Error(err)
			}
		}
		_, _ = store.db.ExecContext(cleanup, "DELETE FROM bridge_devices WHERE name=?", device)
	}()
	s := bridge.NewService(bridge.Config{DefaultDevice: device}, bridge.WithPersistence(store), bridge.WithOutbox(store), bridge.WithAdminReader(store))
	a := bridge.ModuleRegistrationRequest{APIKey: key, WxID: "wxid_self", InstanceID: "installation-presence-a",
		AccountSession: "session-presence-a-0001", AccountGeneration: 1, DeviceModel: "preserved model"}
	if _, err := s.RegisterModule(ctx, a); err != nil {
		t.Fatal(err)
	}
	binding, found, err := store.CurrentAccountBinding(ctx, device)
	if err != nil || !found {
		t.Fatalf("binding: %+v %v", binding, err)
	}
	old := time.Now().UTC().Truncate(time.Second).Add(-10 * time.Minute)
	makeStale := func() {
		t.Helper()
		if _, err := store.db.ExecContext(ctx, "UPDATE bridge_module_installations SET last_seen_at=?,last_active_at=? WHERE device=?", old, old, device); err != nil {
			t.Fatal(err)
		}
		if _, err := store.db.ExecContext(ctx, "UPDATE bridge_module_runtime SET last_register_at=?,last_poll_at=NULL,last_ack_at=NULL,updated_at=? WHERE device=?", old, old, device); err != nil {
			t.Fatal(err)
		}
	}
	readTimes := func() (time.Time, time.Time) {
		t.Helper()
		var installed, runtime time.Time
		if err := store.db.QueryRowContext(ctx, "SELECT last_seen_at FROM bridge_module_installations WHERE device=? AND instance_id=?", device, a.InstanceID).Scan(&installed); err != nil {
			t.Fatal(err)
		}
		if err := store.db.QueryRowContext(ctx, "SELECT updated_at FROM bridge_module_runtime WHERE device=?", device).Scan(&runtime); err != nil {
			t.Fatal(err)
		}
		return installed, runtime
	}
	makeStale()
	// Internal queue scans and a queued old ACK must not use processing time.
	if _, err := s.PollOutbox(ctx, bridge.ModulePollRequest{APIKey: key, WxID: a.WxID, AccountSession: a.AccountSession}); err != nil {
		t.Fatal(err)
	}
	if err := store.RecordModuleActivity(ctx, bridge.ModuleActivity{Kind: "ack", Device: device, ReceivedAt: old}); err != nil {
		t.Fatal(err)
	}
	if online, err := store.ModuleOnline(ctx, device, a.WxID, 5*time.Minute); err != nil || online {
		t.Fatalf("server-only work made phone online: %v %v", online, err)
	}
	if installed, runtime := readTimes(); !installed.Equal(old) || !runtime.Equal(old) {
		t.Fatalf("old/server work refreshed timestamps: %v %v", installed, runtime)
	}

	received := time.Now().UTC().Truncate(time.Second)
	if err := s.ObserveModuleClientActivity(ctx, key, a.WxID, a.AccountSession, received); err != nil {
		t.Fatal(err)
	}
	if installed, runtime := readTimes(); !installed.Equal(received) || !runtime.Equal(received) {
		t.Fatalf("presence views diverged: %v %v", installed, runtime)
	}
	if online, err := store.ModuleOnline(ctx, device, a.WxID, 5*time.Minute); err != nil || !online {
		t.Fatalf("pong did not make phone online: %v %v", online, err)
	}
	rec, _, err := store.FindInstallation(ctx, device, a.InstanceID)
	if err != nil || rec.Info.DeviceModel != a.DeviceModel {
		t.Fatalf("heartbeat erased descriptive fields: %+v %v", rec, err)
	}
	b := a
	b.InstanceID, b.AccountSession = "installation-presence-b", "session-presence-b-0001"
	if _, err := s.RegisterModule(ctx, b); !errors.Is(err, bridge.ErrDeviceStandby) {
		t.Fatalf("live client was displaced: %v", err)
	}

	// Exercise the SQL guard even without the service throttle: older receipts
	// must not overwrite newer timestamps in either table.
	if err := store.WithDeviceAccountLock(ctx, device, func(locked context.Context) error {
		return store.RecordActiveClientPresence(locked, binding, key, old)
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.RecordModuleActivity(ctx, bridge.ModuleActivity{Kind: "poll", Device: device, WxID: a.WxID, ReceivedAt: old}); err != nil {
		t.Fatal(err)
	}
	if installed, runtime := readTimes(); !installed.Equal(received) || !runtime.Equal(received) {
		t.Fatalf("out-of-order receipt rewound state: %v %v", installed, runtime)
	}

	b.Takeover = bridge.TakeoverForeground
	if _, err := s.RegisterModule(ctx, b); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveModuleClientActivity(ctx, key, a.WxID, a.AccountSession, time.Now()); !errors.Is(err, bridge.ErrAccountSession) {
		t.Fatalf("old socket remained authorized: %v", err)
	}
	if _, err := store.db.ExecContext(ctx, "UPDATE bridge_api_keys SET enabled=FALSE,auth_version=auth_version+1 WHERE device=?", device); err != nil {
		t.Fatal(err)
	}
	if err := s.ObserveModuleClientActivity(ctx, key, b.WxID, b.AccountSession, time.Now()); err == nil {
		t.Fatal("disabled credential renewed presence")
	}
}
