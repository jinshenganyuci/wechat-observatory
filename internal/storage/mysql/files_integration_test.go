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

func TestFileOutboxMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skip("disposable MySQL DSN required")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
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
		t.Fatal("file integration requires the explicitly approved scratch database")
	}
	for _, store := range []*Store{first, second} {
		store.db.SetMaxOpenConns(1)
		store.db.SetMaxIdleConns(1)
		if err = store.ApplyMigrations(ctx); err != nil {
			t.Fatal(err)
		}
	}
	device := fmt.Sprintf("files_it_%d", time.Now().UnixNano())
	key := device + "_key"
	if _, err = first.db.ExecContext(ctx, `INSERT INTO bridge_api_keys(code,credential_id,device,nickname) VALUES(?,?,?,?)`, key, key, device, device); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanup, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		for _, table := range []string{"bridge_file_attachments", "bridge_module_outbox", "bridge_module_account_history", "bridge_module_account_current", "bridge_module_installations", "bridge_module_switch_requests", "bridge_module_runtime", "bridge_device_session_lease", "bridge_message_events", "bridge_api_keys"} {
			if _, e := first.db.ExecContext(cleanup, "DELETE FROM "+table+" WHERE device=?", device); e != nil {
				t.Error(e)
			}
		}
		_, _ = first.db.ExecContext(cleanup, "DELETE FROM bridge_devices WHERE name=?", device)
	}()
	spool, err := bridge.NewFileSpool(t.TempDir(), bridge.DefaultFileSpoolBytes)
	if err != nil {
		t.Fatal(err)
	}
	service := func(store *Store) *bridge.Service {
		return bridge.NewService(bridge.Config{DefaultDevice: device, FileSpool: spool}, bridge.WithPersistence(store), bridge.WithOutbox(store))
	}
	s1, s2 := service(first), service(second)
	reg := bridge.ModuleRegistrationRequest{APIKey: key, WxID: "test_owner", InstanceID: "file-installation-0001", AccountSession: "file-session-00000001", AccountGeneration: 1}
	registered, err := s1.RegisterModule(ctx, reg)
	if err != nil {
		t.Fatal(err)
	}
	blob, err := spool.Put(ctx, strings.NewReader("independently stored file contents"))
	if err != nil {
		t.Fatal(err)
	}
	req := bridge.FileEnqueue{Device: device, OwnerWxID: reg.WxID, WxID: "filehelper", IdempotencyHash: strings.Repeat("1", 64), Fingerprint: strings.Repeat("2", 64), Stored: true,
		Attachment: bridge.FileAttachment{ID: blob.ID, Name: "test.txt", Size: blob.Size, SHA256: blob.SHA256, ExpiresAt: time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339)}}
	var wg sync.WaitGroup
	ids := make(chan int64, 2)
	for _, s := range []*bridge.Service{s1, s2} {
		wg.Add(1)
		go func(s *bridge.Service) {
			defer wg.Done()
			item, _, e := s.EnqueueFile(ctx, req, registered.AccountGeneration)
			if e != nil {
				t.Error(e)
			}
			ids <- item.ID
		}(s)
	}
	wg.Wait()
	close(ids)
	var id int64
	for got := range ids {
		if id != 0 && got != id {
			t.Fatal("idempotency duplicated rows")
		}
		id = got
	}
	changed := req
	changed.Fingerprint = strings.Repeat("3", 64)
	if _, _, e := s1.EnqueueFile(ctx, changed, registered.AccountGeneration); !errors.Is(e, bridge.ErrIdempotencyConflict) {
		t.Fatal(e)
	}
	poll := bridge.ModulePollRequest{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession}
	if items, e := s1.PollOutbox(ctx, poll); e != nil || len(items) != 0 {
		t.Fatal("old APK took file", items, e)
	}
	poll.FileProtocol = 1
	items, err := s1.PollOutbox(ctx, poll)
	if err != nil || len(items) != 1 {
		t.Fatal(items, err)
	}
	leased := items[0]
	if leased.ID != id || leased.Kind != "file" || leased.Attachment == nil || leased.Attachment.SHA256 != blob.SHA256 || leased.LeaseToken == "" {
		t.Fatal("wire metadata", leased)
	}
	op := bridge.FileOperation{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, ID: id, LeaseToken: leased.LeaseToken, SHA256: blob.SHA256, Operation: "download"}
	if _, err = s2.FileTask(ctx, op); err != nil {
		t.Fatal(err)
	}
	// Model a revoke between the service's account check and the file SQL
	// transaction: the previously checked credential version must not grant.
	binding, found, err := first.CurrentAccountBinding(ctx, device)
	if err != nil || !found {
		t.Fatal(binding, found, err)
	}
	staleOperation := op
	staleOperation.Device, staleOperation.InstanceID = device, reg.InstanceID
	staleOperation.CredentialID, staleOperation.AuthVersion = binding.CredentialID, binding.AuthVersion
	staleOperation.Operation = "begin"
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_api_keys SET auth_version=auth_version+1 WHERE code=?`, key); err != nil {
		t.Fatal(err)
	}
	if _, e := first.OperateFile(ctx, staleOperation); !errors.Is(e, bridge.ErrAccountSession) {
		t.Fatal("stale credential granted a native send", e)
	}
	staleAck := bridge.ModuleAckRequest{APIKey: key, Device: device, WxID: reg.WxID, AccountSession: reg.AccountSession,
		InstanceID: reg.InstanceID, CredentialID: binding.CredentialID, AuthVersion: binding.AuthVersion}
	if _, _, e := first.ackFile(ctx, staleAck, bridge.ModuleAckItem{ID: id, LeaseToken: leased.LeaseToken, Status: "failed"}); !errors.Is(e, bridge.ErrAccountSession) {
		t.Fatal("stale credential committed an ACK", e)
	}
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_api_keys SET auth_version=?,enabled=FALSE WHERE code=?`, binding.AuthVersion, key); err != nil {
		t.Fatal(err)
	}
	if _, e := first.OperateFile(ctx, staleOperation); !errors.Is(e, bridge.ErrAccountSession) {
		t.Fatal("disabled credential granted a native send", e)
	}
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_api_keys SET enabled=TRUE WHERE code=?`, key); err != nil {
		t.Fatal(err)
	}
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_module_outbox SET lease_until=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	items, err = s2.PollOutbox(ctx, poll)
	if err != nil || len(items) != 1 || items[0].LeaseToken == leased.LeaseToken {
		t.Fatal(items, err)
	}
	op.Operation = "begin"
	if _, e := s1.FileTask(ctx, op); !errors.Is(e, bridge.ErrFileConflict) {
		t.Fatal("expired token accepted", e)
	}
	op.LeaseToken = items[0].LeaseToken
	grants := make(chan bool, 2)
	for _, s := range []*bridge.Service{s1, s2} {
		wg.Add(1)
		go func(s *bridge.Service) {
			defer wg.Done()
			result, e := s.FileTask(ctx, op)
			if e != nil {
				t.Error(e)
			}
			grants <- result.Granted
		}(s)
	}
	wg.Wait()
	close(grants)
	count := 0
	for grant := range grants {
		if grant {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("native grants=%d", count)
	}
	// Neither a session transition, lease expiration nor the 24h sweeper may
	// put a granted task back into the native submission queue.
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_file_attachments SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY) WHERE id=?`, blob.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = first.FileCleanupCandidates(ctx); err != nil {
		t.Fatal(err)
	}
	reg.AccountGeneration = 2
	reg.AccountSession = "file-session-00000002"
	if _, err = s2.RegisterModule(ctx, reg); err != nil {
		t.Fatal(err)
	}
	if _, e := s1.FileTask(ctx, op); !errors.Is(e, bridge.ErrAccountSession) {
		t.Fatal("old account session accepted", e)
	}
	op.AccountSession = reg.AccountSession
	op.Operation = "status"
	result, err := s2.FileTask(ctx, op)
	if err != nil || result.Item.Status != "native_started" {
		t.Fatal(result, err)
	}
	poll.AccountSession = reg.AccountSession
	if got, e := s1.PollOutbox(ctx, poll); e != nil || len(got) != 0 {
		t.Fatal("started re-leased", got, e)
	}
	ack := bridge.ModuleAckRequest{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, Items: []bridge.ModuleAckItem{{ID: id, LeaseToken: op.LeaseToken, Status: "unknown", ChatRecordID: 101}}}
	if got, e := s1.AckOutbox(ctx, ack); e != nil || len(got) != 1 || got[0].Status != "unknown" {
		t.Fatal(got, e)
	}
	if err = s1.CleanupFiles(ctx); err != nil {
		t.Fatal(err)
	}
	f, err := spool.Open(blob.ID)
	if err != nil {
		t.Fatal("unknown blob deleted", err)
	}
	f.Close()
	ack.Items[0].Status = "sent"
	if got, e := s2.AckOutbox(ctx, ack); e != nil || len(got) != 1 {
		t.Fatal(got, e)
	}
	if got, e := s1.AckOutbox(ctx, ack); e != nil || len(got) != 1 || !got[0].AckReplay {
		t.Fatal("terminal ACK replay", got, e)
	}
	if err = s1.CleanupFiles(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err = spool.Open(blob.ID); err == nil {
		t.Fatal("terminal blob retained")
	}
	view, found, err := second.OutboxStatusByID(ctx, id, device, reg.WxID)
	if err != nil || !found || view.Attachment == nil || view.Status != "sent" {
		t.Fatal(view, found, err)
	}
	if _, found, e := second.OutboxStatusByID(ctx, id, device, "other"); e != nil || found {
		t.Fatal("cross-owner item", found, e)
	}
	// Existing retention removes the outbox history, never its dedup tombstone.
	if _, err = first.db.ExecContext(ctx, `DELETE FROM bridge_module_outbox WHERE id=?`, id); err != nil {
		t.Fatal(err)
	}
	req.Stored = false
	if got, replay, e := s2.EnqueueFile(ctx, req, 1); e != nil || !replay || got.ID != id {
		t.Fatal("tombstone lost after retention", got, replay, e)
	}
	// A distinct unstarted expired task is cancelled and its blob is collectible.
	otherBlob, e := spool.Put(ctx, strings.NewReader("other"))
	if e != nil {
		t.Fatal(e)
	}
	req.IdempotencyHash = strings.Repeat("4", 64)
	req.Stored = true
	req.Attachment.ID = otherBlob.ID
	req.Attachment.Size = otherBlob.Size
	req.Attachment.SHA256 = otherBlob.SHA256
	item, _, err := s1.EnqueueFile(ctx, req, 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = first.db.ExecContext(ctx, `UPDATE bridge_file_attachments SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY) WHERE id=?`, otherBlob.ID); err != nil {
		t.Fatal(err)
	}
	if err = s1.CleanupFiles(ctx); err != nil {
		t.Fatal(err)
	}
	view, found, err = first.OutboxStatusByID(ctx, item.ID, device, reg.WxID)
	if err != nil || !found || view.Status != "cancelled" {
		t.Fatal(view, found, err)
	}
}
