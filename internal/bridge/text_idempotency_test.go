package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func textIdempotencyService(t *testing.T, opts ...Option) (*Service, SendTextRequest, http.Handler) {
	t.Helper()
	s := newTestService("", opts...)
	reg := accountRegistration("wxid_self", 1)
	result, err := s.RegisterModule(t.Context(), reg)
	if err != nil {
		t.Fatal(err)
	}
	generation := result.AccountGeneration
	return s, SendTextRequest{Device: "phone-a", OwnerWxID: reg.WxID, AccountGeneration: &generation, WxIDs: []string{"wxid_friend"}, Text: "  original text\n"}, NewHTTPServer(s, "admin").Handler()
}
func idempotentTextHTTP(t *testing.T, h http.Handler, req SendTextRequest, key *string) *httptest.ResponseRecorder {
	t.Helper()
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("POST", "/api/send/text", bytes.NewReader(raw))
	r.Header.Set("X-Bridge-Password", "admin")
	if key != nil {
		r.Header.Set("Idempotency-Key", *key)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func textResponse(t *testing.T, w *httptest.ResponseRecorder) (int64, bool) {
	t.Helper()
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var response struct {
		ID     int64 `json:"outbox_id"`
		Replay bool  `json:"idempotent_replay"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil || response.ID <= 0 {
		t.Fatal(response, err)
	}
	return response.ID, response.Replay
}

// Embedding the original interface intentionally hides the optional capability.
type legacyTextOutbox struct{ Outbox }

func TestModuleStatusAdvertisesActualTextIdempotencyCapability(t *testing.T) {
	for _, supported := range []bool{true, false} {
		t.Run(map[bool]string{true: "supported", false: "legacy"}[supported], func(t *testing.T) {
			s, _, h := textIdempotencyService(t)
			if !supported {
				s.outbox = legacyTextOutbox{s.outbox}
			}
			read := func(auth bool) *httptest.ResponseRecorder {
				req := httptest.NewRequest("GET", "/api/modules/status", nil)
				if auth {
					req.Header.Set("X-Bridge-Password", "admin")
				}
				w := httptest.NewRecorder()
				h.ServeHTTP(w, req)
				return w
			}
			if w := read(false); w.Code != http.StatusUnauthorized {
				t.Fatal("capability endpoint lost authentication", w.Code)
			}
			w := read(true)
			var response struct {
				Modules      []ModuleStatusView `json:"modules"`
				Capabilities map[string]bool    `json:"capabilities"`
			}
			if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &response) != nil {
				t.Fatal(w.Code, w.Body.String())
			}
			value, present := response.Capabilities["text_idempotency"]
			if !present || value != supported {
				t.Fatal("incorrect capability", response.Capabilities)
			}
			if len(response.Modules) != 2 || response.Modules[0].Device != "phone-a" || response.Modules[1].Device != "phone-b" {
				t.Fatal("existing module status changed", response.Modules)
			}
		})
	}
}

func TestTextIdempotencyHTTPReplayValidationAndCompatibility(t *testing.T) {
	s, req, h := textIdempotencyService(t)
	key := "hermes-event-001"
	first, replay := textResponse(t, idempotentTextHTTP(t, h, req, &key))
	if replay {
		t.Fatal("first was replay")
	}
	// Omitted device and whitespace-normalized recipient use the same scope;
	// text itself is deliberately unchanged and remains exact in the fingerprint.
	equivalent := req
	equivalent.Device = ""
	equivalent.WxIDs = []string{" wxid_friend "}
	if id, replay := textResponse(t, idempotentTextHTTP(t, h, equivalent, &key)); id != first || !replay {
		t.Fatal(id, replay)
	}
	if item := snapshotMemoryOutbox(s.outbox.(*MemoryOutbox))[0]; item.Text != req.Text {
		t.Fatal("changed sent text", item.Text)
	}
	changed := req
	changed.Text = strings.TrimSpace(req.Text)
	if w := idempotentTextHTTP(t, h, changed, &key); w.Code != 409 {
		t.Fatal(w.Code, w.Body.String())
	}
	changed = req
	changed.WxIDs = []string{"other"}
	if w := idempotentTextHTTP(t, h, changed, &key); w.Code != 409 {
		t.Fatal(w.Code)
	}
	generation := int64(2)
	changed = req
	changed.AccountGeneration = &generation
	if w := idempotentTextHTTP(t, h, changed, &key); w.Code != 409 {
		t.Fatal(w.Code)
	}
	invalid := []SendTextRequest{req, req, req, req, req, req}
	invalid[0].AccountGeneration = nil
	zero := int64(0)
	invalid[1].AccountGeneration = &zero
	invalid[2].WxIDs = []string{"one", "two"}
	invalid[3].WxIDs = []string{"one,two"}
	invalid[4].WxIDs = []string{" "}
	invalid[5].Text = " \n"
	for _, r := range invalid {
		if w := idempotentTextHTTP(t, h, r, &key); w.Code != 400 {
			t.Fatal(w.Code)
		}
	}
	for _, bad := range []string{"", "short", "has space in key", strings.Repeat("x", 129), "non-ascii-键"} {
		if w := idempotentTextHTTP(t, h, req, &bad); w.Code != 400 {
			t.Fatal(w.Code)
		}
	}
	legacy := req
	legacy.AccountGeneration = nil
	legacy.WxIDs = []string{"one", "two"}
	before := len(snapshotMemoryOutbox(s.outbox.(*MemoryOutbox)))
	a, _ := textResponse(t, idempotentTextHTTP(t, h, legacy, nil))
	b, _ := textResponse(t, idempotentTextHTTP(t, h, legacy, nil))
	if a == b || len(snapshotMemoryOutbox(s.outbox.(*MemoryOutbox))) != before+4 {
		t.Fatal("no-header compatibility changed")
	}
}
func TestTextIdempotencyConcurrencyTerminalTombstoneAndAccountSwitch(t *testing.T) {
	s, req, _ := textIdempotencyService(t)
	key := "concurrent-intent"
	var wg sync.WaitGroup
	ids := make(chan int64, 12)
	replays := make(chan bool, 12)
	for range 12 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id, replay, err := s.SendTextIdempotent(t.Context(), req, key)
			if err != nil {
				t.Error(err)
			}
			ids <- id
			replays <- replay
		}()
	}
	wg.Wait()
	close(ids)
	close(replays)
	var first int64
	for id := range ids {
		if first == 0 {
			first = id
		}
		if id != first {
			t.Fatal(id, first)
		}
	}
	fresh := 0
	for replay := range replays {
		if !replay {
			fresh++
		}
	}
	if fresh != 1 {
		t.Fatal(fresh)
	}
	outbox := s.outbox.(*MemoryOutbox)
	for _, status := range []string{"sent", "failed", "cancelled", "unknown"} {
		outbox.mu.Lock()
		outbox.items[0].Status = status
		outbox.mu.Unlock()
		id, replay, err := s.SendTextIdempotent(t.Context(), req, key)
		if err != nil || id != first || !replay || snapshotMemoryOutbox(outbox)[0].Status != status {
			t.Fatal(status, id, replay, err)
		}
	}
	if _, err := s.RegisterModule(t.Context(), accountRegistration(req.OwnerWxID, 2)); err != nil {
		t.Fatal(err)
	}
	if id, replay, err := s.SendTextIdempotent(t.Context(), req, key); err != nil || id != first || !replay {
		t.Fatal(id, replay, err)
	}
	if _, _, err := s.SendTextIdempotent(t.Context(), req, "new-stale-intent"); !errors.Is(err, ErrAccountSession) {
		t.Fatal(err)
	}
	if _, err := s.RegisterModule(t.Context(), accountRegistration("wxid_other", 3)); err != nil {
		t.Fatal(err)
	}
	if id, replay, err := s.SendTextIdempotent(t.Context(), req, key); err != nil || id != first || !replay {
		t.Fatal(id, replay, err)
	}
	other := req
	other.OwnerWxID = "wxid_other"
	generation := int64(3)
	other.AccountGeneration = &generation
	otherID, replay, err := s.SendTextIdempotent(t.Context(), other, key)
	if err != nil || replay || otherID == first {
		t.Fatal(otherID, replay, err)
	}
	outbox.mu.Lock()
	outbox.items = nil
	outbox.mu.Unlock() // existing history retention
	if id, replay, err := s.SendTextIdempotent(t.Context(), req, key); err != nil || id != first || !replay || len(snapshotMemoryOutbox(outbox)) != 0 {
		t.Fatal(id, replay, err)
	}
}
func TestTextIdempotencyReplayPrecedesOfflineAdmission(t *testing.T) {
	p := &fakeLivenessPersistence{fakePersistence: &fakePersistence{}, online: true}
	s, req, _ := textIdempotencyService(t)
	s.persistence = p
	id, _, err := s.SendTextIdempotent(t.Context(), req, "offline-replay")
	if err != nil {
		t.Fatal(err)
	}
	p.online = false
	if got, replay, err := s.SendTextIdempotent(t.Context(), req, "offline-replay"); err != nil || !replay || got != id {
		t.Fatal(got, replay, err)
	}
	if _, _, err := s.SendTextIdempotent(t.Context(), req, "offline-new-intent"); !errors.Is(err, ErrModuleOffline) {
		t.Fatal(err)
	}
}
func TestMemoryTextIdempotencyAtomicAcrossCallersAndDeviceScope(t *testing.T) {
	store := NewMemoryOutbox()
	req := IdempotentTextEnqueue{Device: "one", OwnerWxID: "owner", WxID: "peer", Text: "x", KeyHash: "key", Fingerprint: "payload"}
	var wg sync.WaitGroup
	ids := make(chan int64, 10)
	for range 10 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id, _, err := store.EnqueueIdempotentText(context.Background(), req)
			if err != nil {
				t.Error(err)
			}
			ids <- id
		}()
	}
	wg.Wait()
	close(ids)
	for id := range ids {
		if id != 1 {
			t.Fatal(id)
		}
	}
	req.Device = "two"
	if id, replay, err := store.EnqueueIdempotentText(t.Context(), req); err != nil || replay || id == 1 {
		t.Fatal(id, replay, err)
	}
}

type lostTextCommitResponse struct {
	*MemoryOutbox
	lost bool
}

func (s *lostTextCommitResponse) EnqueueIdempotentText(ctx context.Context, req IdempotentTextEnqueue) (int64, bool, error) {
	id, replay, err := s.MemoryOutbox.EnqueueIdempotentText(ctx, req)
	if err == nil && !s.lost {
		s.lost = true
		return 0, false, errors.New("injected lost commit response with private SQL details")
	}
	return id, replay, err
}
func TestTextIdempotencyLostCommitResponseRetriesOriginalTask(t *testing.T) {
	s, req, h := textIdempotencyService(t)
	s.outbox = &lostTextCommitResponse{MemoryOutbox: s.outbox.(*MemoryOutbox)}
	key := "lost-commit-response"
	first := idempotentTextHTTP(t, h, req, &key)
	if first.Code != 503 || !strings.Contains(first.Body.String(), "send_unconfirmed") || strings.Contains(first.Body.String(), "private SQL") {
		t.Fatal(first.Code, first.Body.String())
	}
	id, replay := textResponse(t, idempotentTextHTTP(t, h, req, &key))
	if !replay || id != 1 || len(snapshotMemoryOutbox(s.outbox.(*lostTextCommitResponse).MemoryOutbox)) != 1 {
		t.Fatal(id, replay)
	}
}
