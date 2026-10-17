package bridge

import (
	"context"
	"errors"
	"fmt"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
	"time"
)

func TestSlowPresenceWriterDoesNotBlockReceiptsAndCoalescesOriginalTimes(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	receipts := make(chan time.Time, 1)
	started := make(chan time.Time, 2)
	release := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		observeClientReceipts(ctx, cancel, receipts, func(ctx context.Context, at time.Time) error {
			started <- at
			select {
			case <-release:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		})
	}()
	first := time.Date(2026, 10, 17, 1, 0, 0, 0, time.UTC)
	publishClientReceipt(receipts, first)
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("writer did not start")
	}
	// While the only writer is blocked, receiving many further frames remains
	// bounded/nonblocking and retains their receive time rather than a later now.
	latest := first
	for i := 1; i <= 100; i++ {
		latest = first.Add(time.Duration(i) * time.Second)
		publishClientReceipt(receipts, latest)
	}
	select {
	case <-started:
		t.Fatal("spawned concurrent presence writes")
	default:
	}
	close(release)
	select {
	case got := <-started:
		if !got.Equal(latest) {
			t.Fatalf("queued receipt changed/lost: %v", got)
		}
	case <-time.After(time.Second):
		t.Fatal("pending latest receipt not processed")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("presence worker did not stop with connection")
	}
}

func TestPresenceFailureCancelsConnectionWithoutProcessingFurtherFrames(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	receipts := make(chan time.Time, 1)
	receipts <- time.Now()
	calls := 0
	observeClientReceipts(ctx, cancel, receipts, func(context.Context, time.Time) error {
		calls++
		return ErrAccountSession
	})
	if ctx.Err() == nil || calls != 1 {
		t.Fatal("failed current-session check did not cancel socket")
	}
}

func TestLatestPongCannotBeCrowdedOutByAQueuedPreProbePong(t *testing.T) {
	mailbox := make(chan time.Time, 1)
	beforeProbe := time.Date(2026, 10, 17, 1, 0, 0, 0, time.UTC)
	probeStarted := beforeProbe.Add(time.Second)
	response := probeStarted.Add(time.Second)
	mailbox <- beforeProbe
	publishClientReceipt(mailbox, response)
	if got := <-mailbox; got.Before(probeStarted) || !got.Equal(response) {
		t.Fatalf("fresh probe response was lost: %v", got)
	}
	mailbox <- response
	publishClientReceipt(mailbox, beforeProbe)
	if got := <-mailbox; !got.Equal(response) {
		t.Fatal("queued timestamp moved backward")
	}
}

func TestClientPresenceKeepsBothViewsLiveWithoutRegistration(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 15; i++ {
		clock.Advance(clientPresenceInterval)
		if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now()); err != nil {
			t.Fatal(err)
		}
	}
	status := moduleStatus(t, s, "phone-a")
	if status.RuntimeStatus == "offline" || status.Installations[0].State != InstallationStateActive {
		t.Fatalf("live client became offline: %+v", status)
	}
	if _, err := s.RegisterModule(t.Context(), phoneRegistration("b", "wxid_self", 1)); !errors.Is(err, ErrDeviceStandby) {
		t.Fatalf("client heartbeat did not prevent stale takeover: %v", err)
	}
}

func TestServerPollsAndLeaseRenewalsCannotKeepAbsentClientOnline(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	lease, err := s.AcquireOutboxSession(t.Context(), a.APIKey, "phone-a", a.WxID, a.AccountSession)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 11; i++ {
		clock.Advance(30 * time.Second)
		if _, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: a.APIKey, WxID: a.WxID, AccountSession: a.AccountSession}); err != nil {
			t.Fatal(err)
		}
		if _, err := s.RenewOutboxSession(t.Context(), lease); err != nil {
			t.Fatal(err)
		}
	}
	status := moduleStatus(t, s, "phone-a")
	if status.RuntimeStatus != "offline" || status.Installations[0].State != InstallationStateOffline {
		t.Fatalf("server work invented client activity: %+v", status)
	}
	if result, err := s.RegisterModule(t.Context(), phoneRegistration("b", "wxid_self", 1)); err != nil || result.Takeover != takeoverStale {
		t.Fatalf("silent phone prevented legitimate stale takeover: %+v %v", result, err)
	}
}

func TestQueuedClientReceiptDoesNotRefreshAtProcessingTime(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	clock.Advance(90 * time.Second)
	received := clock.Now()
	clock.Advance(6 * time.Minute)
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, received); err != nil {
		t.Fatal(err)
	}
	status := moduleStatus(t, s, "phone-a")
	if status.RuntimeStatus != "offline" || status.Installations[0].State != InstallationStateOffline ||
		status.Installations[0].LastSeenAt != formatTimeRFC3339Nano(received) {
		t.Fatalf("delayed receipt rejuvenated presence: %+v", status)
	}
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, received.Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	if got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt; got != formatTimeRFC3339Nano(received) {
		t.Fatalf("out-of-order receipt rewound presence: %s", got)
	}
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now().Add(time.Second)); err == nil {
		t.Fatal("future timestamp accepted")
	}
}

func TestClientPresenceThrottleAndRevokedScope(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	initial := clock.Now()
	clock.Advance(24 * time.Second)
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now()); err != nil {
		t.Fatal(err)
	}
	if got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt; got != formatTimeRFC3339Nano(initial) {
		t.Fatal("heartbeat was not throttled")
	}
	clock.Advance(time.Second)
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now()); err != nil {
		t.Fatal(err)
	}
	if got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt; got != formatTimeRFC3339Nano(clock.Now()) {
		t.Fatal("heartbeat at interval was lost")
	}
	b := phoneRegistration("b", "wxid_self", 1)
	b.Takeover = TakeoverForeground
	if _, err := s.RegisterModule(t.Context(), b); err != nil {
		t.Fatal(err)
	}
	clock.Advance(30 * time.Second)
	if err := s.ObserveModuleClientActivity(t.Context(), a.APIKey, a.WxID, a.AccountSession, clock.Now()); !errors.Is(err, ErrAccountSession) {
		t.Fatalf("displaced session renewed presence: %v", err)
	}
	before := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt
	key, _, _ := s.lookupAPIKey(t.Context(), b.APIKey)
	key.Disabled = true
	s.setCachedAPIKey(key)
	if err := s.ObserveModuleClientActivity(t.Context(), b.APIKey, b.WxID, b.AccountSession, clock.Now()); err == nil {
		t.Fatal("disabled key renewed presence")
	}
	if got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt; got != before {
		t.Fatal("rejected heartbeat changed presence")
	}
}

func TestActualPollReceiptRenewsInstallationButInternalPollDoesNotRecordActivity(t *testing.T) {
	s, clock := newClockedService(t)
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	clock.Advance(30 * time.Second)
	received := clock.Now()
	ctx := withClientReceipt(t.Context(), received)
	if _, err := s.PollOutbox(ctx, ModulePollRequest{APIKey: a.APIKey, WxID: a.WxID, AccountSession: a.AccountSession}); err != nil {
		t.Fatal(err)
	}
	if got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt; got != formatTimeRFC3339Nano(received) {
		t.Fatal("HTTP poll receipt was not recorded")
	}

	persistence := &fakePersistence{}
	legacy := newTestService("", WithPersistence(persistence))
	if _, err := legacy.PollOutbox(t.Context(), ModulePollRequest{APIKey: testAPIKey, WxID: "wxid_self"}); err != nil {
		t.Fatal(err)
	}
	_, activities := persistence.activitySnapshot()
	if len(activities) != 0 {
		t.Fatal("server poll recorded client activity")
	}
	if _, err := legacy.PollOutbox(withClientReceipt(t.Context(), received), ModulePollRequest{APIKey: testAPIKey, WxID: "wxid_self"}); err != nil {
		t.Fatal(err)
	}
	_, activities = persistence.activitySnapshot()
	if len(activities) != 1 || !activities[0].ReceivedAt.Equal(received) {
		t.Fatal("actual receive time was lost")
	}
}

func TestWebSocketPongsWhileAwaitingAckKeepInstallationAndRuntimeOnline(t *testing.T) {
	s := newTestService("")
	var clock atomic.Int64
	clock.Store(time.Now().UnixNano())
	s.clock = func() time.Time { return time.Unix(0, clock.Load()) }
	a := phoneRegistration("a", "wxid_self", 1)
	if _, err := s.RegisterModule(t.Context(), a); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(NewHTTPServer(s, "admin").Handler())
	defer server.Close()
	path := fmt.Sprintf("/module/outbox/ws?api_key=%s&wxid=%s&account_session=%s",
		url.QueryEscape(a.APIKey), url.QueryEscape(a.WxID), url.QueryEscape(a.AccountSession))
	conn := dialTestWebSocket(t, server.URL, path)
	defer conn.close()
	if ready := readTestWSMessage(t, conn); ready.Type != "ready" {
		t.Fatalf("unexpected ready: %+v", ready)
	}
	if _, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", WxIDs: []string{"friend"}, Text: "leave ACK outstanding"}); err != nil {
		t.Fatal(err)
	}
	if first := readTestWSMessageOfType(t, conn, "outbox"); len(first.Items) != 1 {
		t.Fatal("missing in-flight message")
	}
	for i := 0; i < 13; i++ {
		clock.Add(int64(clientPresenceInterval))
		want := s.now()
		if !conn.writeControl(wsOpPong, []byte("ping")) {
			t.Fatal("write pong")
		}
		deadline := time.Now().Add(time.Second)
		for {
			got := moduleStatus(t, s, "phone-a").Installations[0].LastSeenAt
			if got == formatTimeRFC3339Nano(want) {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("pong did not refresh presence while awaiting ACK: %s", got)
			}
			time.Sleep(time.Millisecond)
		}
	}
	status := moduleStatus(t, s, "phone-a")
	if status.RuntimeStatus == "offline" || status.Installations[0].State != InstallationStateActive {
		t.Fatalf("live pong stream offline: %+v", status)
	}
	if _, err := s.RegisterModule(t.Context(), phoneRegistration("b", "wxid_self", 1)); !errors.Is(err, ErrDeviceStandby) {
		t.Fatalf("live socket displaced: %v", err)
	}
}
