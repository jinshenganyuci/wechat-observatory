package bridge

import (
	"bytes"
	"log"
	"strings"
	"testing"
	"time"
)

func TestOutboxTraceDefaultsOffAndUsesOnlyFixedNumericMetadata(t *testing.T) {
	s := newTestService("")
	var output bytes.Buffer
	s.outboxTraceLog = log.New(&output, "", 0)
	s.traceOutbox(outboxTraceWSWritten, 42, 3, 12*time.Millisecond)
	if output.Len() != 0 {
		t.Fatal("trace emitted without opt-in")
	}
	s.cfg.OutboxTrace = true
	s.traceOutbox(outboxTraceWSWritten, 42, 3, 12*time.Millisecond)
	s.traceOutbox(outboxTraceAckReceived, 42, 0, 0)
	s.traceOutbox(outboxTraceStage(255), 42, 3, 0)
	s.traceOutbox(outboxTraceWSWritten, 0, 3, 0)
	want := "outbox_trace task_id=42 stage=ws_written attempt=3 duration_ms=12\n" +
		"outbox_trace task_id=42 stage=ack_received attempt=0 duration_ms=0\n"
	if output.String() != want {
		t.Fatalf("unexpected diagnostic fields: %s", output.String())
	}
}

func TestOutboxTraceCoversCommitAndReplayWithoutMessageOrCredential(t *testing.T) {
	s := newTestService("")
	var output bytes.Buffer
	s.outboxTraceLog = log.New(&output, "", 0)
	s.cfg.OutboxTrace = true
	id, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"private-recipient"}, Text: "private-message-body"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: testAPIKey, WxID: "wxid_self"}); err != nil {
		t.Fatal(err)
	}
	ack := ModuleAckRequest{APIKey: testAPIKey, WxID: "wxid_self", Items: []ModuleAckItem{{ID: id, Status: "failed", Error: "private-error-payload"}}}
	for i := 0; i < 2; i++ {
		if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 1 {
			t.Fatalf("ACK/retry %d: %+v %v", i, items, err)
		}
	}
	trace := output.String()
	for _, stage := range []string{"enqueue_committed", "lease_committed", "ack_committed", "ack_replayed"} {
		if strings.Count(trace, "stage="+stage+" ") != 1 {
			t.Fatalf("missing/duplicate stage %s: %s", stage, trace)
		}
	}
	for _, private := range []string{testAPIKey, "private-recipient", "private-message-body", "private-error-payload", "wxid_self"} {
		if strings.Contains(trace, private) {
			t.Fatal("trace contains request data or a raw error")
		}
	}
}
