package mysql

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"wechat-observatory/internal/bridge"
)

func TestFileMaintenanceMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skip("disposable MySQL DSN required")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	defer cancel()
	a, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	var db string
	if err = a.db.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&db); err != nil {
		t.Fatal(err)
	}
	if db != "observatory_ci_test" && db != "observatory_test" {
		t.Fatal("file maintenance integration requires scratch database")
	}
	for _, s := range []*Store{a, b} {
		s.db.SetMaxOpenConns(1)
		s.db.SetMaxIdleConns(1)
		if err = s.ApplyMigrations(ctx); err != nil {
			t.Fatal(err)
		}
	}
	device := fmt.Sprintf("file_maintenance_%d", time.Now().UnixNano())
	key := device + "_key"
	if _, err = a.db.ExecContext(ctx, `INSERT INTO bridge_api_keys(code,credential_id,device,nickname) VALUES(?,?,?,?)`, key, key, device, device); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanup, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		for _, table := range []string{"bridge_file_attachments", "bridge_module_outbox", "bridge_module_account_history", "bridge_module_account_current", "bridge_module_installations", "bridge_module_switch_requests", "bridge_module_runtime", "bridge_device_session_lease", "bridge_api_keys"} {
			if _, e := a.db.ExecContext(cleanup, "DELETE FROM "+table+" WHERE device=?", device); e != nil {
				t.Error(e)
			}
		}
		_, _ = a.db.ExecContext(cleanup, "DELETE FROM bridge_devices WHERE name=?", device)
	}()
	dir := t.TempDir()
	spool, err := bridge.NewFileSpool(dir, bridge.DefaultFileSpoolBytes)
	if err != nil {
		t.Fatal(err)
	}
	services := []*bridge.Service{bridge.NewService(bridge.Config{DefaultDevice: device, FileSpool: spool}, bridge.WithPersistence(a), bridge.WithOutbox(a)), bridge.NewService(bridge.Config{DefaultDevice: device, FileSpool: spool}, bridge.WithPersistence(b), bridge.WithOutbox(b))}
	reg := bridge.ModuleRegistrationRequest{APIKey: key, WxID: "maintenance_owner", InstanceID: "maintenance-install-0001", AccountSession: "maintenance-session-001", AccountGeneration: 1}
	registered, err := services[0].RegisterModule(ctx, reg)
	if err != nil {
		t.Fatal(err)
	}
	sequence := 0
	enqueue := func(t *testing.T) (bridge.ModuleOutboxItem, bridge.FileEnqueue) {
		t.Helper()
		sequence++
		blob, e := spool.Put(ctx, strings.NewReader(fmt.Sprintf("bytes %d", sequence)))
		if e != nil {
			t.Fatal(e)
		}
		req := bridge.FileEnqueue{Device: device, OwnerWxID: reg.WxID, WxID: "filehelper", IdempotencyHash: fmt.Sprintf("%064d", sequence), Fingerprint: fmt.Sprintf("%064d", sequence), Stored: true,
			Attachment: bridge.FileAttachment{ID: blob.ID, Name: "test.txt", Size: blob.Size, SHA256: blob.SHA256, ExpiresAt: time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339)}}
		item, _, e := services[0].EnqueueFile(ctx, req, registered.AccountGeneration)
		if e != nil {
			t.Fatal(e)
		}
		return item, req
	}
	poll := func(t *testing.T) bridge.ModuleOutboxItem {
		t.Helper()
		items, e := services[0].PollOutbox(ctx, bridge.ModulePollRequest{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, FileProtocol: 1, Limit: 1})
		if e != nil || len(items) != 1 {
			t.Fatal(items, e)
		}
		return items[0]
	}
	operation := func(item bridge.ModuleOutboxItem, op string) bridge.FileOperation {
		return bridge.FileOperation{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, ID: item.ID, LeaseToken: item.LeaseToken, SHA256: item.Attachment.SHA256, Operation: op}
	}
	cleanup := func(item bridge.ModuleOutboxItem, mode string) bridge.FileCleanupRequest {
		return bridge.FileCleanupRequest{Device: device, OwnerWxID: reg.WxID, ID: item.ID, Mode: mode}
	}
	t.Run("unknown_copy_only_and_recovery", func(t *testing.T) {
		_, req := enqueue(t)
		item := poll(t)
		if result, e := services[0].FileTask(ctx, operation(item, "begin")); e != nil || !result.Granted {
			t.Fatal(result, e)
		}
		ack := bridge.ModuleAckRequest{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, Items: []bridge.ModuleAckItem{{ID: item.ID, LeaseToken: item.LeaseToken, Status: "unknown", ChatRecordID: 42, Error: "unconfirmed"}}}
		if got, e := services[0].AckOutbox(ctx, ack); e != nil || len(got) != 1 {
			t.Fatal(got, e)
		}
		if _, e := services[1].CleanupFileTask(ctx, bridge.FileCleanupRequest{Device: "other", OwnerWxID: reg.WxID, ID: item.ID, Mode: "server_only"}); !errors.Is(e, bridge.ErrFileTaskNotFound) {
			t.Fatal(e)
		}
		if _, found, e := b.FileTaskViewByID(ctx, item.ID, device, "other"); e != nil || found {
			t.Fatal(found, e)
		}
		view, e := services[1].CleanupFileTask(ctx, cleanup(item, "server_only"))
		if e != nil || view.Status != "unknown" || view.ServerCopy.State != "removed" || view.PhoneCopyState != "not_reported" {
			t.Fatal(view, e)
		}
		if _, e = os.Stat(filepath.Join(dir, item.Attachment.ID)); !os.IsNotExist(e) {
			t.Fatal(e)
		}
		status, e := services[0].FileTask(ctx, operation(item, "status"))
		if e != nil || status.Item.LeaseToken != item.LeaseToken || status.Item.NativeStartedAt == "" {
			t.Fatal(status, e)
		}
		if _, e = services[0].FileTask(ctx, operation(item, "download")); !errors.Is(e, bridge.ErrFileConflict) {
			t.Fatal(e)
		}
		if got, replay, e := services[1].EnqueueFile(ctx, req, 0); e != nil || !replay || got.ID != item.ID {
			t.Fatal(got, replay, e)
		}
		views, e := b.ListFileTasks(ctx, bridge.FileTaskFilter{Device: device, OwnerWxID: reg.WxID, Status: "unconfirmed"})
		if e != nil || len(views) != 1 || views[0].ID != item.ID {
			t.Fatal(views, e)
		}
		if got, e := services[1].PollOutbox(ctx, bridge.ModulePollRequest{APIKey: key, WxID: reg.WxID, AccountSession: reg.AccountSession, FileProtocol: 1}); e != nil || len(got) != 0 {
			t.Fatal("removed unknown was leased", got, e)
		}
		ack.Items[0].Status = "sent"
		ack.Items[0].Error = ""
		if got, e := services[1].AckOutbox(ctx, ack); e != nil || len(got) != 1 || got[0].Status != "sent" {
			t.Fatal(got, e)
		}
	})
	t.Run("row_lock_guards_begin_and_renew", func(t *testing.T) {
		for _, op := range []string{"begin", "renew"} {
			for _, expired := range []bool{false, true} {
				enqueue(t)
				item := poll(t)
				if expired {
					if _, e := a.db.ExecContext(ctx, "UPDATE bridge_module_outbox SET lease_until=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?", item.ID); e != nil {
						t.Fatal(e)
					}
				}
				// Bypass the shared account lock for the admin side: its SQL row lock
				// itself must serialize against the module's transaction on another pod.
				var wg sync.WaitGroup
				wg.Add(2)
				var opErr, cleanupErr error
				go func() { defer wg.Done(); _, opErr = services[0].FileTask(ctx, operation(item, op)) }()
				go func() { defer wg.Done(); _, cleanupErr = b.RequestFileCleanup(ctx, cleanup(item, "cancel_unstarted")) }()
				wg.Wait()
				if expired {
					if cleanupErr != nil || !errors.Is(opErr, bridge.ErrFileConflict) {
						t.Fatal(op, expired, opErr, cleanupErr)
					}
				} else {
					if opErr != nil || !errors.Is(cleanupErr, bridge.ErrFileConflict) {
						t.Fatal(op, expired, opErr, cleanupErr)
					}
				}
				if !expired && op == "renew" {
					if _, e := a.db.ExecContext(ctx, "UPDATE bridge_module_outbox SET lease_until=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=?", item.ID); e != nil {
						t.Fatal(e)
					}
					if _, e := services[0].CleanupFileTask(ctx, cleanup(item, "cancel_unstarted")); e != nil {
						t.Fatal(e)
					}
				}
				if !expired && op == "begin" {
					if _, e := services[0].CleanupFileTask(ctx, cleanup(item, "server_only")); e != nil {
						t.Fatal(e)
					}
				}
				if expired {
					status, e := services[0].FileTask(ctx, operation(item, "status"))
					if e != nil || status.Item.Status != "cancelled" || status.Item.LeaseToken != item.LeaseToken {
						t.Fatal(status, e)
					}
				}
			}
		}
	})
	t.Run("durable_failure_retry_and_pagination", func(t *testing.T) {
		item, _ := enqueue(t)
		// Persist a request but lose the HTTP response before any unlink.
		if _, e := a.RequestFileCleanup(ctx, cleanup(item, "cancel_unstarted")); e != nil {
			t.Fatal(e)
		}
		if e := b.RecordFileCleanupFailure(ctx, item.Attachment.ID, "remove_failed"); e != nil {
			t.Fatal(e)
		}
		view, found, e := b.FileTaskViewByID(ctx, item.ID, device, reg.WxID)
		if e != nil || !found || !view.CanCleanupServer || view.ServerCopy.State != "cleanup_pending" || view.ServerCopy.LastError != "remove_failed" {
			t.Fatal(view, e)
		}
		// Even expired terminal history must remain findable until bytes are gone.
		if _, e = a.db.ExecContext(ctx, "UPDATE bridge_module_outbox SET updated_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 20 DAY) WHERE id=?", item.ID); e != nil {
			t.Fatal(e)
		}
		_, query, e := retentionQueries(15)
		if e != nil {
			t.Fatal(e)
		}
		// Restrict the production retention predicate to this fixture's scope.
		query = strings.Replace(query, "WHERE status", "WHERE device=? AND status", 1)
		textResult, e := a.db.ExecContext(ctx, `INSERT INTO bridge_module_outbox(device,owner_wxid,wxid,text,status,updated_at)
		 VALUES(?,?,?,'test','sent',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 20 DAY))`, device, reg.WxID, "filehelper")
		if e != nil {
			t.Fatal(e)
		}
		textID, e := textResult.LastInsertId()
		if e != nil {
			t.Fatal(e)
		}
		if _, e = a.db.ExecContext(ctx, query, device, 100); e != nil {
			t.Fatal(e)
		}
		if _, found, e = b.FileTaskViewByID(ctx, item.ID, device, reg.WxID); e != nil || !found {
			t.Fatal("retained history purged", found, e)
		}
		var count int
		if e = a.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM bridge_module_outbox WHERE id=?", textID).Scan(&count); e != nil || count != 0 {
			t.Fatal("text retention changed", count, e)
		}
		view, e = services[1].CleanupFileTask(ctx, cleanup(item, "cancel_unstarted"))
		if e != nil || view.ServerCopy.State != "removed" || view.ServerCopy.LastError != "" {
			t.Fatal(view, e)
		}
		if _, e = a.db.ExecContext(ctx, query, device, 100); e != nil {
			t.Fatal(e)
		}
		if _, found, e = b.FileTaskViewByID(ctx, item.ID, device, reg.WxID); e != nil || found {
			t.Fatal("removed file history not purged", found, e)
		}
		for i := 0; i < 4; i++ {
			enqueue(t)
		}
		first, e := a.ListFileTasks(ctx, bridge.FileTaskFilter{Device: device, OwnerWxID: reg.WxID, Limit: 2})
		if e != nil || len(first) != 3 {
			t.Fatal(first, e)
		}
		second, e := b.ListFileTasks(ctx, bridge.FileTaskFilter{Device: device, OwnerWxID: reg.WxID, Limit: 2, BeforeID: first[1].ID})
		if e != nil || len(second) < 2 || second[0].ID >= first[1].ID {
			t.Fatal(second, e)
		}
		other, e := a.ListFileTasks(ctx, bridge.FileTaskFilter{Device: device, OwnerWxID: "other"})
		if e != nil || len(other) != 0 {
			t.Fatal(other, e)
		}
	})
}
