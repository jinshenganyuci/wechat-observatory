package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

type failingPresencePersistence struct {
	*fakePersistence
	calls int
}

func (p *failingPresencePersistence) RecordActiveClientPresence(context.Context, AccountBinding, string, time.Time) error {
	p.calls++
	return errors.New("temporary presence storage failure")
}

func TestAckSurvivesAuxiliaryPresenceFailureAndConfirmsRetryWithoutRepublishing(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	p := &failingPresencePersistence{fakePersistence: &fakePersistence{}}
	s.persistence = p
	id, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: "ACK must survive"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: a.APIKey, WxID: a.WxID, AccountSession: a.AccountSession}); err != nil {
		t.Fatal(err)
	}
	clock.Advance(30 * time.Second)
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now()); err == nil || p.calls != 1 {
		t.Fatal("presence failure setup did not exercise persistent writer")
	}
	req := ModuleAckRequest{APIKey: a.APIKey, WxID: a.WxID, AccountSession: a.AccountSession,
		Items: []ModuleAckItem{{ID: id, Status: "sent", ChatRecordID: 12345}}}
	for i := 0; i < 2; i++ {
		items, err := s.AckOutbox(withClientReceipt(t.Context(), clock.Now()), req)
		if err != nil || len(items) != 1 || items[0].Status != "sent" {
			t.Fatalf("ACK/retry %d: items=%+v err=%v", i, items, err)
		}
	}
	if p.calls != 1 || len(p.outboundEvents) != 1 {
		t.Fatalf("ACK depended on presence or republished event: writes=%d events=%d", p.calls, len(p.outboundEvents))
	}
	// A conflicting replay must neither overwrite nor confirm the old result.
	req.Items[0].Status = "failed"
	if items, err := s.AckOutbox(t.Context(), req); err != nil || len(items) != 0 {
		t.Fatalf("conflicting ACK confirmed: %+v %v", items, err)
	}
	req.Items[0].Status = "sent"
	req.Items[0].ChatRecordID++
	if items, err := s.AckOutbox(t.Context(), req); err != nil || len(items) != 0 {
		t.Fatalf("different chat record confirmed: %+v %v", items, err)
	}
	// Terminal retry confirmation still goes through current session/key checks.
	b := phoneRegistration("b", "wxid_self", 1)
	b.Takeover = TakeoverForeground
	s.persistence = nil // Keep this test's bindings in the in-memory account store.
	if _, err := s.RegisterModule(t.Context(), b); err != nil {
		t.Fatal(err)
	}
	s.persistence = p
	if _, err := s.AckOutbox(t.Context(), req); !errors.Is(err, ErrAccountSession) {
		t.Fatalf("old session confirmed terminal ACK: %v", err)
	}
}

func TestPreviouslyLiveWebSocketStillProbesBeforeDeliveryAfterFreshnessExpires(t *testing.T) {
	outbox := NewMemoryOutbox()
	s := newTestService("", WithOutbox(outbox))
	var clock atomic.Int64
	clock.Store(time.Now().UnixNano())
	s.clock = func() time.Time { return time.Unix(0, clock.Load()) }
	server := httptest.NewServer(NewHTTPServer(s, "admin").Handler())
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key=wechat-a-key&wxid=wxid_self")
	defer conn.close()
	readTestWSMessage(t, conn)
	if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: "first"}); err != nil {
		t.Fatal(err)
	}
	first := readTestWSMessageOfType(t, conn, "outbox")
	if !conn.writeJSON(outboxWSMessage{Type: "ack", Ack: &ModuleAckRequest{Items: []ModuleAckItem{{ID: first.Items[0].ID, Status: "sent"}}}}) {
		t.Fatal("write ACK")
	}
	readTestWSMessageOfType(t, conn, "ack")
	clock.Add(int64(outboxWSActivityFreshness + time.Second))
	if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: "stale socket"}); err != nil {
		t.Fatal(err)
	}
	if err := conn.conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	payload, op, err := conn.readFrame()
	if err != nil || op != wsOpPing {
		t.Fatalf("stale inbound proof allowed delivery: opcode=%d err=%v", op, err)
	}
	if items := snapshotMemoryOutbox(outbox); items[1].Status != "pending" || items[1].AttemptCount != 0 {
		t.Fatalf("stale socket leased task before new pong: %+v", items)
	}
	if !conn.writeControl(wsOpPong, payload) {
		t.Fatal("write probe pong")
	}
	if second := readTestWSMessageOfType(t, conn, "outbox"); len(second.Items) != 1 || second.Items[0].Text != "stale socket" {
		t.Fatalf("fresh probe did not resume delivery: %+v", second)
	}
}

func TestTransientPresenceFailureKeepsConnectionAndUsesNextRealReceipt(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	receipts := make(chan time.Time, 1)
	calls := make(chan time.Time, 3)
	done := make(chan struct{})
	go func() {
		defer close(done)
		count := 0
		observeClientReceipts(ctx, cancel, receipts, func(_ context.Context, at time.Time) error {
			calls <- at
			count++
			if count == 1 {
				return errors.New("temporary DB failure")
			}
			return nil
		})
	}()
	first := time.Now()
	receipts <- first
	<-calls
	if ctx.Err() != nil {
		t.Fatal("temporary presence error closed healthy transport")
	}
	latest := first.Add(time.Second)
	publishClientReceipt(receipts, latest)
	select {
	case got := <-calls:
		if !got.Equal(latest) {
			t.Fatalf("retry invented receive time: %v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("next actual receipt was not retried")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("presence worker did not cancel")
	}
}

type blockedAckOutbox struct {
	*MemoryOutbox
	started   chan struct{}
	cancelled chan struct{}
}

func (o *blockedAckOutbox) AckReplyActions(ctx context.Context, _ ModuleAckRequest) ([]ModuleOutboxItem, error) {
	close(o.started)
	<-ctx.Done()
	close(o.cancelled)
	return nil, ctx.Err()
}

func TestWebSocketAnswersPingWhileAckStorageWaitsAndCancelsWorkerOnClose(t *testing.T) {
	outbox := &blockedAckOutbox{MemoryOutbox: NewMemoryOutbox(), started: make(chan struct{}), cancelled: make(chan struct{})}
	s := newTestService("", WithOutbox(outbox))
	server := httptest.NewServer(NewHTTPServer(s, "admin").Handler())
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key=wechat-a-key&wxid=wxid_self")
	defer conn.close()
	readTestWSMessage(t, conn)
	if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: "blocked ACK"}); err != nil {
		t.Fatal(err)
	}
	first := readTestWSMessageOfType(t, conn, "outbox")
	if !conn.writeJSON(outboxWSMessage{Type: "ack", Ack: &ModuleAckRequest{Items: []ModuleAckItem{{ID: first.Items[0].ID, Status: "sent"}}}}) {
		t.Fatal("write ACK")
	}
	select {
	case <-outbox.started:
	case <-time.After(time.Second):
		t.Fatal("ACK storage did not start")
	}
	if !conn.writeControl(wsOpPing, []byte("while-db-blocked")) {
		t.Fatal("write ping")
	}
	if err := conn.conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	payload, op, err := conn.readFrame()
	if err != nil || op != wsOpPong || string(payload) != "while-db-blocked" {
		t.Fatalf("ACK database wait blocked socket control frames: opcode=%d err=%v", op, err)
	}
	conn.close()
	select {
	case <-outbox.cancelled:
	case <-time.After(time.Second):
		t.Fatal("connection close did not cancel ACK storage worker")
	}
}

func TestOutboxMessageQueueSendStopsWhenConnectionCloses(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	queue := make(chan outboxWSMessage, 1)
	queue <- outboxWSMessage{Type: "ack"}
	done := make(chan bool, 1)
	go func() { done <- sendOutboxWSMessage(ctx, queue, outboxWSMessage{Type: "wake"}) }()
	cancel()
	select {
	case sent := <-done:
		if sent {
			t.Fatal("send to full queue succeeded after cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled connection leaked blocked outgoing sender")
	}
}

func TestPresenceDatabaseOperationHasItsOwnDeadline(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	receipts := make(chan time.Time, 1)
	receipts <- time.Now()
	finished := make(chan error, 1)
	go observeClientReceipts(ctx, cancel, receipts, func(operation context.Context, _ time.Time) error {
		<-operation.Done()
		finished <- operation.Err()
		return operation.Err()
	})
	select {
	case err := <-finished:
		if !errors.Is(err, context.DeadlineExceeded) || ctx.Err() != nil {
			t.Fatalf("presence timeout revoked healthy connection: operation=%v connection=%v", err, ctx.Err())
		}
	case <-time.After(outboxWSPresenceTimeout + time.Second):
		t.Fatal("presence storage operation remained unbounded")
	}
}

func TestOutboxAckDeadlineClosesPongingConnectionWithoutReleasingTaskEarly(t *testing.T) {
	outbox := NewMemoryOutbox()
	s := newTestService("", WithOutbox(outbox))
	h := NewHTTPServer(s, "admin")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.outboxWebSocketWithAckTimeout(w, r, 150*time.Millisecond)
	}))
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key=wechat-a-key&wxid=wxid_self")
	defer conn.close()
	if ready := readTestWSMessage(t, conn); ready.Type != "ready" {
		t.Fatal("missing ready")
	}
	for _, text := range []string{"first", "second"} {
		if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	if first := readTestWSMessageOfType(t, conn, "outbox"); len(first.Items) != 1 {
		t.Fatal("missing first delivery")
	}
	if !conn.writeControl(wsOpPong, []byte("still alive")) {
		t.Fatal("pong failed")
	}
	if err := conn.conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := conn.readFrame(); err == nil {
		t.Fatal("unacknowledged task left socket open indefinitely")
	} else if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("only the test read deadline closed the wait; server ACK timer did not close the socket")
	}
	outbox.mu.Lock()
	defer outbox.mu.Unlock()
	if len(outbox.items) != 2 || outbox.items[0].Status != "leased" || outbox.items[0].AttemptCount != 1 ||
		outbox.items[0].leaseUntil.Before(time.Now().Add(50*time.Second)) || outbox.items[1].Status != "pending" {
		t.Fatalf("timeout released/retried or bypassed uncertain send: %+v", outbox.items)
	}
}

func TestFreshWebSocketDeliveryAndDuplicateAckDoNotBlockOrReleaseAnotherTask(t *testing.T) {
	outbox := NewMemoryOutbox()
	s := newTestService("", WithOutbox(outbox))
	server := httptest.NewServer(NewHTTPServer(s, "admin").Handler())
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key=wechat-a-key&wxid=wxid_self")
	defer conn.close()
	readTestWSMessage(t, conn)
	send := func(text string) {
		t.Helper()
		if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	ack := func(id int64) {
		t.Helper()
		if !conn.writeJSON(outboxWSMessage{Type: "ack", Ack: &ModuleAckRequest{Items: []ModuleAckItem{{ID: id, Status: "sent", ChatRecordID: id}}}}) {
			t.Fatal("write ACK failed")
		}
	}
	send("first")
	first := readTestWSMessageOfType(t, conn, "outbox")
	ack(first.Items[0].ID)
	readTestWSMessageOfType(t, conn, "ack")
	send("second")
	if err := conn.conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	payload, op, err := conn.readFrame()
	if err != nil || op != wsOpText {
		t.Fatalf("fresh connection waited for redundant probe: opcode=%d err=%v", op, err)
	}
	var second outboxWSMessage
	if err := json.Unmarshal(payload, &second); err != nil || second.Type != "outbox" || len(second.Items) != 1 {
		t.Fatalf("missing immediate second delivery: %s %v", payload, err)
	}
	send("third")
	ack(first.Items[0].ID) // Response to a retry must not clear second's inFlight.
	if duplicate := readTestWSMessageOfType(t, conn, "ack"); len(duplicate.Items) != 1 || duplicate.Items[0].ID != first.Items[0].ID {
		t.Fatalf("committed duplicate not confirmed: %+v", duplicate)
	}
	time.Sleep(50 * time.Millisecond)
	items := snapshotMemoryOutbox(outbox)
	if items[1].Status != "leased" || items[2].Status != "pending" {
		t.Fatalf("old ACK released different task: %+v", items)
	}
	ack(second.Items[0].ID)
	readTestWSMessageOfType(t, conn, "ack")
	if third := readTestWSMessageOfType(t, conn, "outbox"); len(third.Items) != 1 || third.Items[0].Text != "third" {
		t.Fatalf("queue did not resume: %+v", third)
	}
}
