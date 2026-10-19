package mysql

import (
	"context"
	"fmt"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"wechat-observatory/internal/bridge"
)

func TestNativeMentionMetadataMySQLIntegration(t *testing.T) {
	dsn := strings.TrimSpace(os.Getenv(offlineOutboxIntegrationDSNEnv))
	if dsn == "" {
		t.Skipf("set %s to run the disposable MySQL integration test", offlineOutboxIntegrationDSNEnv)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	store, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	var database string
	if err := store.db.QueryRowContext(ctx, `SELECT DATABASE()`).Scan(&database); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.ToLower(database), "test") {
		t.Fatalf("refusing integration writes to non-test database %q", database)
	}

	// On a fresh test database this reproduces the pre-mention table, without
	// dropping data when the test suite already created the shared test schema.
	for _, statement := range Migrations() {
		if strings.Contains(statement, "CREATE TABLE IF NOT EXISTS bridge_message_events") {
			legacy := strings.Replace(statement, "\n\t\t\tmentioned_wxids TEXT NULL,", "", 1)
			if _, err := store.db.ExecContext(ctx, legacy); err != nil {
				t.Fatal(err)
			}
		}
	}
	for range 2 {
		if err := store.ApplyMigrations(ctx); err != nil {
			t.Fatal(err)
		}
	}
	device := fmt.Sprintf("mentions_it_%d", time.Now().UnixNano())
	t.Cleanup(func() {
		cleanup, done := context.WithTimeout(context.Background(), 10*time.Second)
		defer done()
		_, _ = store.db.ExecContext(cleanup, `DELETE FROM bridge_message_events WHERE device = ?`, device)
	})
	want := []string{"wxid_self", "wxid_other"}
	event := bridge.MessageEvent{ID: "201", Device: device, OwnerWxID: "wxid_self", From: "group@chatroom",
		To: "wxid_self", RoomID: "group@chatroom", Sender: "wxid_admin", Text: "@robot hello",
		Direction: bridge.DirectionRecv, MessageType: 1, CreateTime: 1786530000, MentionedWxIDs: want}
	stored, err := store.RecordInboundEvent(ctx, event)
	if err != nil || !slices.Equal(stored.MentionedWxIDs, want) {
		t.Fatalf("recorded=%+v error=%v", stored, err)
	}
	// A retry returns the same immutable source event, without creating a second
	// cursor or allowing later unverified metadata to change the first record.
	event.MentionedWxIDs = []string{"wxid_replacement"}
	replayed, err := store.RecordInboundEvent(ctx, event)
	if err != nil || replayed.Sequence != stored.Sequence || !slices.Equal(replayed.MentionedWxIDs, want) {
		t.Fatalf("duplicate changed persisted metadata: %+v error=%v", replayed, err)
	}
	legacy := event
	legacy.ID = "202"
	legacy.MentionedWxIDs = nil
	old, err := store.RecordInboundEvent(ctx, legacy)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.db.ExecContext(ctx, `UPDATE bridge_message_events SET mentioned_wxids = NULL WHERE id = ?`, old.Sequence); err != nil {
		t.Fatal(err)
	}

	stream, err := store.ListLiveEventsAfter(ctx, stored.Sequence-1, device, 10)
	if err != nil || len(stream) != 2 || !slices.Equal(stream[0].MentionedWxIDs, want) || len(stream[1].MentionedWxIDs) != 0 {
		t.Fatalf("live replay=%+v error=%v", stream, err)
	}
	history, err := store.ListMessages(ctx, bridge.MessageFilter{Device: device, OwnerWxID: "wxid_self", AfterIDSet: true, Limit: 10})
	if err != nil || len(history) != 2 || !slices.Equal(history[0].MentionedWxIDs, want) || len(history[1].MentionedWxIDs) != 0 {
		t.Fatalf("history=%+v error=%v", history, err)
	}
	all, err := store.ListStoredEvents(ctx, 500)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, item := range all {
		if item.ID == stored.Sequence {
			found = slices.Equal(item.MentionedWxIDs, want)
		}
	}
	if !found {
		t.Fatal("stored-events view lost mention metadata")
	}
}
