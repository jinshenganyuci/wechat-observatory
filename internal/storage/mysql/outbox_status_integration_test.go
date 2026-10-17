package mysql

import (
	"context"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"wechat-observatory/internal/bridge"
)

func TestOutboxStatusMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skip("set " + offlineOutboxIntegrationDSNEnv + " to a disposable test database")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	s, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	var database string
	if err := s.db.QueryRowContext(ctx, "SELECT DATABASE()").Scan(&database); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.ToLower(database), "test") {
		t.Fatal("integration requires a test database")
	}
	if err := s.ApplyMigrations(ctx); err != nil {
		t.Fatal(err)
	}
	device := fmt.Sprintf("outbox_view_%d", time.Now().UnixNano())
	defer func() {
		cleanup, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		if _, err := s.db.ExecContext(cleanup, "DELETE FROM bridge_module_outbox WHERE device IN (?,?)", device, device+"_other"); err != nil {
			t.Error(err)
		}
	}()
	for _, action := range []bridge.ReplyAction{
		{Device: device, OwnerWxID: "self", WxID: "friend", Text: "first"},
		{Device: device, OwnerWxID: "self", WxID: "other", Text: "second"},
		{Device: device, OwnerWxID: "another-account", WxID: "friend", Text: "private"},
		{Device: device + "_other", OwnerWxID: "self", WxID: "friend", Text: "private"},
	} {
		if _, err := s.EnqueueReply(ctx, action); err != nil {
			t.Fatal(err)
		}
	}
	filter := bridge.OutboxStatusFilter{Device: device, OwnerWxID: "self"}
	items, err := s.ListOutboxStatuses(ctx, filter)
	if err != nil || len(items) != 2 || items[0].Text != "first" || items[1].Text != "second" {
		t.Fatalf("scoped FIFO: %+v %v", items, err)
	}
	if items[0].Status != "pending" || items[0].AttemptCount != 0 || items[0].LeaseUntil != "" {
		t.Fatalf("read mutated task: %+v", items[0])
	}
	leased, err := s.PollReplyActions(ctx, bridge.ModulePollRequest{Device: device, WxID: "self", Limit: 1})
	if err != nil || len(leased) != 1 || leased[0].ID != items[0].ID {
		t.Fatalf("read leased task: %+v %v", leased, err)
	}
	items, err = s.ListOutboxStatuses(ctx, filter)
	if err != nil || items[0].LeaseUntil == "" || items[0].Status != "leased" || items[0].AttemptCount != 1 {
		t.Fatalf("lease view: %+v %v", items, err)
	}
	_, err = s.AckReplyActions(ctx, bridge.ModuleAckRequest{Device: device, WxID: "self", Items: []bridge.ModuleAckItem{{ID: leased[0].ID, Status: "failed", Error: "test failure"}}})
	if err != nil {
		t.Fatal(err)
	}
	filter.Status = "failed"
	items, err = s.ListOutboxStatuses(ctx, filter)
	if err != nil || len(items) != 1 || items[0].LastError != "test failure" || items[0].LeaseUntil != "" {
		t.Fatalf("failed view: %+v %v", items, err)
	}
	filter.Status, filter.Limit = "all", 1
	items, err = s.ListOutboxStatuses(ctx, filter)
	if err != nil || len(items) != 1 || items[0].Text != "second" {
		t.Fatalf("recent limit: %+v %v", items, err)
	}
	filter.WxID = "friend"
	items, err = s.ListOutboxStatuses(ctx, filter)
	if err != nil || len(items) != 1 || items[0].Text != "first" {
		t.Fatalf("recipient scope: %+v %v", items, err)
	}
	filter.OwnerWxID = ""
	if _, err := s.ListOutboxStatuses(ctx, filter); err == nil {
		t.Fatal("unscoped query accepted")
	}
}
