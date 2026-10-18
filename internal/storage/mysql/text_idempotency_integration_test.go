package mysql

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"wechat-observatory/internal/bridge"
)

func TestTextIdempotencyMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skip("disposable MySQL DSN required")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 60*time.Second)
	defer cancel()
	first, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	var database string
	if err = first.db.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&database); err != nil {
		t.Fatal(err)
	}
	if database != "observatory_ci_test" && database != "observatory_test" {
		t.Fatal("text idempotency requires approved scratch database")
	}
	for _, store := range []*Store{first, second} {
		store.db.SetMaxOpenConns(1)
		store.db.SetMaxIdleConns(1)
		if err = store.ApplyMigrations(ctx); err != nil {
			t.Fatal(err)
		}
	}
	device := fmt.Sprintf("text_idem_%d", time.Now().UnixNano())
	secondDevice := device + "_other"
	key := device + "_key"
	if _, err = first.db.ExecContext(ctx, `INSERT INTO bridge_api_keys(code,credential_id,device,nickname) VALUES(?,?,?,?)`, key, key, device, device); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanup, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		for _, table := range []string{"bridge_text_idempotency", "bridge_module_outbox", "bridge_module_account_history", "bridge_module_account_current", "bridge_module_installations", "bridge_module_switch_requests", "bridge_module_runtime", "bridge_device_session_lease", "bridge_api_keys"} {
			if _, e := first.db.ExecContext(cleanup, "DELETE FROM "+table+" WHERE device IN (?,?)", device, secondDevice); e != nil {
				t.Error(e)
			}
		}
		_, _ = first.db.ExecContext(cleanup, "DELETE FROM bridge_devices WHERE name IN (?,?)", device, secondDevice)
	}()
	service := func(store *Store) *bridge.Service {
		return bridge.NewService(bridge.Config{DefaultDevice: device}, bridge.WithPersistence(store), bridge.WithOutbox(store))
	}
	s1, s2 := service(first), service(second)
	reg := bridge.ModuleRegistrationRequest{APIKey: key, WxID: "text_owner", InstanceID: "text-idempotency-install", AccountSession: "text-idempotency-session1", AccountGeneration: 1}
	registered, err := s1.RegisterModule(ctx, reg)
	if err != nil {
		t.Fatal(err)
	}
	req := bridge.SendTextRequest{Device: device, OwnerWxID: reg.WxID, AccountGeneration: &registered.AccountGeneration, WxIDs: []string{"peer"}, Text: "original text"}
	const idempotencyKey = "hermes-response-001"
	type result struct {
		id     int64
		replay bool
		err    error
	}
	results := make(chan result, 2)
	for _, s := range []*bridge.Service{s1, s2} {
		go func(s *bridge.Service) {
			id, replay, e := s.SendTextIdempotent(ctx, req, idempotencyKey)
			results <- result{id, replay, e}
		}(s)
	}
	a, b := <-results, <-results
	if a.err != nil || b.err != nil || a.id != b.id || a.replay == b.replay {
		t.Fatal(a, b)
	}
	id := a.id
	// Terminal and ambiguous outcomes are lookup-only, never new queue entries.
	for _, status := range []string{"sent", "failed", "cancelled", "unknown"} {
		if _, err = first.db.ExecContext(ctx, "UPDATE bridge_module_outbox SET status=? WHERE id=?", status, id); err != nil {
			t.Fatal(err)
		}
		if got, replay, e := s2.SendTextIdempotent(ctx, req, idempotencyKey); e != nil || !replay || got != id {
			t.Fatal(status, got, replay, e)
		}
		var actual string
		if err = first.db.QueryRowContext(ctx, "SELECT status FROM bridge_module_outbox WHERE id=?", id).Scan(&actual); err != nil || actual != status {
			t.Fatal(actual, err)
		}
	}
	if _, err = first.db.ExecContext(ctx, "UPDATE bridge_module_runtime SET updated_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR) WHERE device=?", device); err != nil {
		t.Fatal(err)
	}
	if got, replay, e := s1.SendTextIdempotent(ctx, req, idempotencyKey); e != nil || !replay || got != id {
		t.Fatal("offline replay", got, replay, e)
	}
	if _, _, e := s1.SendTextIdempotent(ctx, req, "new-offline-intent"); !errors.Is(e, bridge.ErrModuleOffline) {
		t.Fatal(e)
	}
	reg.AccountGeneration = 2
	reg.AccountSession = "text-idempotency-session2"
	if _, err = s1.RegisterModule(ctx, reg); err != nil {
		t.Fatal(err)
	}
	if got, replay, e := s2.SendTextIdempotent(ctx, req, idempotencyKey); e != nil || !replay || got != id {
		t.Fatal("old generation replay", got, replay, e)
	}
	if _, _, e := s2.SendTextIdempotent(ctx, req, "new-stale-generation"); !errors.Is(e, bridge.ErrAccountSession) {
		t.Fatal(e)
	}
	changed := req
	changed.Text = "different"
	if _, _, e := s1.SendTextIdempotent(ctx, changed, idempotencyKey); !errors.Is(e, bridge.ErrIdempotencyConflict) {
		t.Fatal(e)
	}
	reg.WxID = "other_owner"
	reg.AccountGeneration = 3
	reg.AccountSession = "text-idempotency-session3"
	current, err := s1.RegisterModule(ctx, reg)
	if err != nil {
		t.Fatal(err)
	}
	if got, replay, e := s2.SendTextIdempotent(ctx, req, idempotencyKey); e != nil || !replay || got != id {
		t.Fatal("old owner replay", got, replay, e)
	}
	other := req
	other.OwnerWxID = reg.WxID
	other.AccountGeneration = &current.AccountGeneration
	otherID, replay, err := s2.SendTextIdempotent(ctx, other, idempotencyKey)
	if err != nil || replay || otherID == id {
		t.Fatal(otherID, replay, err)
	}
	// Exercise actual retention SQL, not a fake deletion. The tombstone survives.
	if _, err = first.db.ExecContext(ctx, "UPDATE bridge_module_outbox SET status='sent',updated_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 20 DAY) WHERE id=?", id); err != nil {
		t.Fatal(err)
	}
	_, retention, err := retentionQueries(15)
	if err != nil {
		t.Fatal(err)
	}
	retention = strings.Replace(retention, "WHERE status", "WHERE device=? AND status", 1)
	if _, err = first.db.ExecContext(ctx, retention, device, 100); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = first.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM bridge_module_outbox WHERE id=?", id).Scan(&count); err != nil || count != 0 {
		t.Fatal(count, err)
	}
	if got, replay, e := s1.SendTextIdempotent(ctx, req, idempotencyKey); e != nil || !replay || got != id {
		t.Fatal("retention replay", got, replay, e)
	}
	// Independent store calls bypass service locks to prove SQL atomicity itself.
	enqueue := bridge.IdempotentTextEnqueue{Device: device, OwnerWxID: reg.WxID, WxID: "peer", Text: "direct atomic", KeyHash: strings.Repeat("a", 64), Fingerprint: strings.Repeat("b", 64)}
	var wg sync.WaitGroup
	direct := make(chan result, 8)
	for i := 0; i < 8; i++ {
		store := first
		if i%2 != 0 {
			store = second
		}
		wg.Add(1)
		go func(s *Store) {
			defer wg.Done()
			id, replay, e := s.EnqueueIdempotentText(ctx, enqueue)
			direct <- result{id, replay, e}
		}(store)
	}
	wg.Wait()
	close(direct)
	var directID int64
	fresh := 0
	for r := range direct {
		if r.err != nil {
			t.Fatal(r.err)
		}
		if directID == 0 {
			directID = r.id
		}
		if directID != r.id {
			t.Fatal(r)
		}
		if !r.replay {
			fresh++
		}
	}
	if fresh != 1 {
		t.Fatal(fresh)
	}
	enqueue.Device = secondDevice
	if otherID, replay, e := second.EnqueueIdempotentText(ctx, enqueue); e != nil || replay || otherID == directID {
		t.Fatal("device isolation", otherID, replay, e)
	}
	// The idempotency reservation must roll back when outbox insertion fails.
	if _, err = first.db.ExecContext(ctx, "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'"); err != nil {
		t.Fatal(err)
	}
	enqueue.Device = device
	enqueue.KeyHash = strings.Repeat("c", 64)
	enqueue.WxID = strings.Repeat("x", 192)
	if _, _, e := first.EnqueueIdempotentText(ctx, enqueue); e == nil {
		t.Fatal("expected invalid outbox recipient insert failure")
	}
	if _, found, e := second.LookupTextIdempotency(ctx, enqueue); e != nil || found {
		t.Fatal("rollback left unusable tombstone", found, e)
	}
}
