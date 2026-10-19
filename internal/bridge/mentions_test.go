package bridge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestMentionMetadataDoesNotChangeSourceEventIdentity(t *testing.T) {
	base := MessageEvent{Device: "phone-a", OwnerWxID: "wxid_self", ID: "101", EventID: 101,
		Direction: DirectionRecv, From: "group@chatroom", To: "wxid_self", RoomID: "group@chatroom",
		Sender: "wxid_admin", Text: "@robot hello", MessageType: 1, CreateTime: 1786530000}
	withMentions := base
	withMentions.MentionedWxIDs = []string{"wxid_self", "wxid_other"}
	if base.CanonicalEventKey() != withMentions.CanonicalEventKey() || base.CanonicalEventKeyV2() != withMentions.CanonicalEventKeyV2() {
		t.Fatal("mention metadata changed the existing event identity")
	}
	var legacy MessageEvent
	if err := json.Unmarshal([]byte(`{"text":"@robot <msgsource><atuserlist>wxid_self</atuserlist></msgsource>"}`), &legacy); err != nil {
		t.Fatal(err)
	}
	if len(legacy.Normalize().MentionedWxIDs) != 0 {
		t.Fatal("legacy text must not be interpreted as verified mentions")
	}
}

func TestMentionMetadataBoundsBeforePersistence(t *testing.T) {
	base := MessageEvent{Device: "phone-a", From: "group@chatroom", Text: "hello", Direction: DirectionRecv}
	for name, ids := range map[string][]string{
		"old client": nil,
		"empty list": {},
		"native IDs": {"wxid_self", "wxid_other"},
	} {
		t.Run(name, func(t *testing.T) {
			event := base
			event.MentionedWxIDs = ids
			if err := event.Validate(); err != nil {
				t.Fatal(err)
			}
		})
	}
	tooMany := make([]string, 513)
	tooLarge := make([]string, 512)
	for i := range tooLarge {
		tooLarge[i] = strings.Repeat("x", 191)
	}
	for name, ids := range map[string][]string{
		"too many":          tooMany,
		"empty ID":          {""},
		"oversized ID":      {strings.Repeat("x", 192)},
		"surrounding space": {" wxid_self"},
		"oversized JSON":    tooLarge,
	} {
		t.Run(name, func(t *testing.T) {
			persistence := &fakePersistence{}
			service := newTestService("", WithPersistence(persistence))
			event := base
			event.APIKey = testAPIKey
			event.MentionedWxIDs = ids
			if _, err := service.Ingest(t.Context(), event); err == nil {
				t.Fatal("invalid mentions accepted")
			}
			if len(persistence.inboundEvents) != 0 {
				t.Fatal("invalid mentions reached persistence")
			}
		})
	}
}

func TestIngestAndHistoryPreserveNativeMentionMetadata(t *testing.T) {
	want := []string{"wxid_self", "wxid_other"}
	persistence := &fakePersistence{}
	service := newTestService("", WithPersistence(persistence))
	result, err := service.Ingest(t.Context(), MessageEvent{APIKey: testAPIKey, Device: "phone-a",
		ID: "mention-101", Direction: DirectionRecv, From: "group@chatroom", To: "wxid_self",
		RoomID: "group@chatroom", Sender: "wxid_admin", Text: "@robot hello", MessageType: 1,
		MentionedWxIDs: want})
	if err != nil || result == nil || !result.Published {
		t.Fatalf("ingestion failed: result=%+v error=%v", result, err)
	}
	if len(persistence.inboundEvents) != 1 || !slices.Equal(persistence.inboundEvents[0].MentionedWxIDs, want) {
		t.Fatalf("mention metadata lost during ingestion: %+v", persistence.inboundEvents)
	}
	reader := &fakeAdminReader{messages: []StoredEventView{{ID: 101, SequenceID: 101,
		Device: "phone-a", RoomID: "group@chatroom", SenderWxID: "wxid_admin", MentionedWxIDs: want}}}
	server := NewHTTPServer(newTestService("", WithAdminReader(reader)), "admin").Handler()
	req := httptest.NewRequest(http.MethodGet, "/api/messages?device=phone-a&after_id=100", nil)
	req.Header.Set("X-Bridge-Password", "admin")
	rec := httptest.NewRecorder()
	server.ServeHTTP(rec, req)
	var response struct {
		Messages []StoredEventView `json:"messages"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil || rec.Code != http.StatusOK ||
		len(response.Messages) != 1 || !slices.Equal(response.Messages[0].MentionedWxIDs, want) {
		t.Fatalf("history lost mention metadata: status=%d body=%s error=%v", rec.Code, rec.Body.String(), err)
	}
}

func TestDurableSSEReplayPreservesNativeMentionMetadata(t *testing.T) {
	tailer := &fakeEventTailReader{fakeAdminReader: &fakeAdminReader{}, latest: 4,
		events: []MessageEvent{{Sequence: 4, EventKey: "evt_4", Device: "phone-a",
			RoomID: "group@chatroom", Sender: "wxid_admin", Direction: DirectionRecv,
			Text: "@robot hello", MentionedWxIDs: []string{"wxid_self"}}}}
	server := NewHTTPServer(newTestService("", WithAdminReader(tailer)), "admin").Handler()
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	req := httptest.NewRequest(http.MethodGet, "/api/live/events?device=phone-a", nil).WithContext(ctx)
	req.Header.Set("X-Bridge-Password", "admin")
	req.Header.Set("Last-Event-ID", "3")
	rec := newSSERecorder()
	done := make(chan struct{})
	go func() { server.ServeHTTP(rec, req); close(done) }()
	deadline := time.After(time.Second)
	for !strings.Contains(rec.String(), "id: 4\n") {
		select {
		case <-deadline:
			t.Fatal("durable mention event was not replayed")
		case <-time.After(10 * time.Millisecond):
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("SSE handler did not stop")
	}
	if !strings.Contains(rec.String(), `"mentioned_wxids":["wxid_self"]`) {
		t.Fatalf("durable SSE lost mention metadata: %s", rec.String())
	}
}
