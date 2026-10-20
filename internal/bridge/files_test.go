package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func fileService(t *testing.T) (*Service, ModuleRegistrationRequest, http.Handler) {
	t.Helper()
	s := newTestService("")
	spool, err := NewFileSpool(t.TempDir(), DefaultFileSpoolBytes)
	if err != nil {
		t.Fatal(err)
	}
	s.fileSpool = spool
	r := accountRegistration("wxid_self", 1)
	if _, err = s.RegisterModule(t.Context(), r); err != nil {
		t.Fatal(err)
	}
	return s, r, NewHTTPServer(s, "admin").Handler()
}
func uploadFile(t *testing.T, h http.Handler, key, name, content string, generation int64, admin bool) *httptest.ResponseRecorder {
	t.Helper()
	return uploadFileTo(t, h, key, name, content, "filehelper", generation, admin)
}

func uploadFileTo(t *testing.T, h http.Handler, key, name, content, recipient string, generation int64, admin bool) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	for k, v := range map[string]string{"device": "phone-a", "owner_wxid": "wxid_self", "account_generation": fmt.Sprint(generation), "wxid": recipient} {
		if err := form.WriteField(k, v); err != nil {
			t.Fatal(err)
		}
	}
	part, err := form.CreateFormFile("file", name)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.WriteString(part, content)
	_ = form.Close()
	r := httptest.NewRequest("POST", "/api/send/file", &body)
	r.Header.Set("Content-Type", form.FormDataContentType())
	r.Header.Set("Idempotency-Key", key)
	if admin {
		r.Header.Set("X-Bridge-Password", "admin")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestFileUploadPreservesUnicodeAndLiteralPercentNames(t *testing.T) {
	for _, name := range []string{"自定义物资[最新版].txt", "literal%20%E8%87%AA[1].txt"} {
		t.Run(name, func(t *testing.T) {
			service, registration, handler := fileService(t)
			response := uploadFile(t, handler, "filename-roundtrip", name, "small file fixture", 1, true)
			if response.Code != http.StatusOK {
				t.Fatal(response.Code, response.Body.String())
			}
			item := filePoll(t, service, registration)
			if item.Attachment == nil || item.Attachment.Name != name {
				t.Fatalf("filename changed between upload and phone delivery: want %q, got %+v", name, item.Attachment)
			}
		})
	}
}

func TestFileUploadToFriendPreservesRecipientIdempotencyAndLease(t *testing.T) {
	s, registration, handler := fileService(t)
	const recipient = "wxid_test_friend"
	request := func(key, to string, generation int64, admin bool) *httptest.ResponseRecorder {
		return uploadFileTo(t, handler, key, "notice.txt", "test file content", to, generation, admin)
	}
	if response := request("friend-upload", recipient, 1, false); response.Code != http.StatusUnauthorized {
		t.Fatal("friend upload bypassed admin auth", response.Code)
	}
	for _, invalid := range []string{"", strings.Repeat("x", 192)} {
		if response := request("invalid-recipient", invalid, 1, true); response.Code != http.StatusBadRequest {
			t.Fatal("invalid recipient accepted", response.Code)
		}
	}
	if response := request("wrong-generation", recipient, 99, true); response.Code != http.StatusConflict {
		t.Fatal("friend upload bypassed account generation", response.Code)
	}
	var id int64
	for attempt := 0; attempt < 2; attempt++ {
		response := request("friend-upload", recipient, 1, true)
		var result struct {
			ID     int64 `json:"outbox_id"`
			Replay bool  `json:"idempotent_replay"`
		}
		if response.Code != http.StatusOK || json.Unmarshal(response.Body.Bytes(), &result) != nil || result.ID <= 0 {
			t.Fatal("friend file rejected", response.Code, response.Body.String())
		}
		if attempt == 0 {
			id = result.ID
		}
		if result.ID != id || result.Replay != (attempt == 1) {
			t.Fatal("friend request duplicated", result)
		}
	}
	if response := request("friend-upload", "wxid_other_friend", 1, true); response.Code != http.StatusConflict {
		t.Fatal("same key changed recipient", response.Code)
	}
	item := filePoll(t, s, registration)
	if item.ID != id || item.WxID != recipient || item.OwnerWxID != registration.WxID || item.LeaseToken == "" {
		t.Fatal("friend identity lost during lease", item)
	}
	result, err := s.FileTask(t.Context(), fileOp(registration, item, "renew"))
	if err != nil || result.Item.ID != id || result.Item.WxID != recipient || result.Item.LeaseToken != item.LeaseToken {
		t.Fatal("friend lease changed on renewal", result, err)
	}
}
func filePoll(t *testing.T, s *Service, r ModuleRegistrationRequest) ModuleOutboxItem {
	t.Helper()
	items, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: 1})
	if err != nil || len(items) != 1 || items[0].Kind != "file" {
		t.Fatalf("poll %+v %v", items, err)
	}
	return items[0]
}
func fileOp(r ModuleRegistrationRequest, item ModuleOutboxItem, operation string) FileOperation {
	return FileOperation{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, ID: item.ID, LeaseToken: item.LeaseToken, SHA256: item.Attachment.SHA256, Operation: operation}
}

func TestFileAbandonSerializesWithNativeBegin(t *testing.T) {
	for _, first := range []string{"begin", "abandon", "race"} {
		t.Run(first, func(t *testing.T) {
			s, registration, handler := fileService(t)
			if response := uploadFile(t, handler, "abandon-test", "a.txt", "file body", 1, true); response.Code != 200 {
				t.Fatal(response.Code)
			}
			item := filePoll(t, s, registration)
			var granted, cancelled bool
			apply := func(operation string) (FileTaskResult, error) {
				return s.FileTask(t.Context(), fileOp(registration, item, operation))
			}
			if first == "race" {
				type outcome struct {
					result FileTaskResult
					err    error
				}
				outcomes := make(chan outcome, 2)
				for _, operation := range []string{"begin", "abandon"} {
					go func() { result, err := apply(operation); outcomes <- outcome{result, err} }()
				}
				for range 2 {
					outcome := <-outcomes
					if outcome.err != nil && !errors.Is(outcome.err, ErrFileConflict) {
						t.Fatal(outcome.err)
					}
					granted = granted || outcome.result.Granted
					cancelled = cancelled || outcome.result.Item.Status == "cancelled"
				}
			} else {
				result, err := apply(first)
				if err != nil {
					t.Fatal(err)
				}
				granted, cancelled = result.Granted, result.Item.Status == "cancelled"
				second := "begin"
				if first == "begin" {
					second = "abandon"
				}
				if _, err = apply(second); !errors.Is(err, ErrFileConflict) {
					t.Fatal("conflicting transition accepted", err)
				}
			}
			if granted == cancelled {
				t.Fatalf("grant=%t cancelled=%t", granted, cancelled)
			}
			if cancelled {
				result, err := apply("abandon")
				if err != nil || result.Granted || result.Item.Status != "cancelled" {
					t.Fatal("lost abandon response not replayable", result, err)
				}
			}
			if err := s.CleanupFiles(t.Context()); err != nil {
				t.Fatal(err)
			}
			file, err := s.fileSpool.Open(item.Attachment.ID)
			if granted && err != nil {
				t.Fatal("abandon deleted granted bytes", err)
			}
			if cancelled && err == nil {
				t.Fatal("cancelled bytes retained")
			}
			if file != nil {
				file.Close()
			}
		})
	}
}

func TestFileAbandonConfirmsSessionCancelledIntentAfterRestart(t *testing.T) {
	s, old, handler := fileService(t)
	if response := uploadFile(t, handler, "abandon-restart", "a.txt", "file body", 1, true); response.Code != 200 {
		t.Fatal(response.Code)
	}
	item := filePoll(t, s, old)
	current := accountRegistration(old.WxID, 2)
	if _, err := s.RegisterModule(t.Context(), current); err != nil {
		t.Fatal(err)
	}
	result, err := s.FileTask(t.Context(), fileOp(current, item, "abandon"))
	if err != nil || result.Item.Status != "cancelled" || result.Item.NativeStartedAt != "" {
		t.Fatal(result, err)
	}
	if _, err = s.FileTask(t.Context(), fileOp(old, item, "abandon")); !errors.Is(err, ErrAccountSession) {
		t.Fatal("old session accepted", err)
	}
}

func TestFileUploadIdempotencyQuotaAndValidation(t *testing.T) {
	s, r, h := fileService(t)
	if got := uploadFile(t, h, "idem-key-1", "a.txt", "file body", 1, false); got.Code != 401 {
		t.Fatalf("admin bypass: %d", got.Code)
	}
	first := uploadFile(t, h, "idem-key-1", "a.txt", "file body", 1, true)
	if first.Code != 200 {
		t.Fatal(first.Code, first.Body.String())
	}
	var initial struct {
		ID int64 `json:"outbox_id"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &initial)
	// Changed generation/online state must not invalidate the original idempotent response.
	r = accountRegistration(r.WxID, 2)
	if _, err := s.RegisterModule(t.Context(), r); err != nil {
		t.Fatal(err)
	}
	s.fileSpool.quota = 1
	retry := uploadFile(t, h, "idem-key-1", "a.txt", "file body", 1, true)
	if retry.Code != 200 || !strings.Contains(retry.Body.String(), `"idempotent_replay":true`) {
		t.Fatal(retry.Code, retry.Body.String())
	}
	var replay struct {
		ID int64 `json:"outbox_id"`
	}
	_ = json.Unmarshal(retry.Body.Bytes(), &replay)
	if initial.ID != replay.ID {
		t.Fatal("duplicate enqueue")
	}
	if got := uploadFile(t, h, "idem-key-1", "a.txt", "changed", 1, true); got.Code != 409 {
		t.Fatal(got.Code, got.Body.String())
	}
	if got := uploadFile(t, h, "idem-key-2", "a.txt", "file body", 2, true); got.Code != 507 {
		t.Fatal(got.Code, got.Body.String())
	}
	for _, name := range []string{"x.PNG", "clip.mp4", "bad\\path.txt"} {
		if got := uploadFile(t, h, "idem-key-x", name, "x", 2, true); got.Code != 400 {
			t.Fatal(name, got.Code)
		}
	}
	if got := uploadFile(t, h, "idem-key-x", "empty.txt", "", 2, true); got.Code != 400 {
		t.Fatal(got.Code)
	}
	if got := uploadFile(t, h, "", "a.txt", "x", 2, true); got.Code != 400 {
		t.Fatal(got.Code)
	}
	// Reject an oversized declared request before reading a body. Do not build
	// a 300 MiB fixture just to exercise this header boundary.
	oversized := httptest.NewRequest("POST", "/api/send/file", &rejectReadBody{t: t})
	oversized.ContentLength = MaxFileBytes + (64 << 10) + 1
	oversized.Header.Set("X-Bridge-Password", "admin")
	oversized.Header.Set("Idempotency-Key", "idem-big-1")
	response := httptest.NewRecorder()
	h.ServeHTTP(response, oversized)
	if response.Code != 413 || !strings.Contains(response.Body.String(), "300 MiB") {
		t.Fatal(response.Code, response.Body.String())
	}
	entries, _ := os.ReadDir(s.fileSpool.dir)
	if len(entries) != 2 {
		t.Fatalf("failed/replayed uploads left blobs: %d", len(entries))
	}
}

type rejectReadBody struct{ t *testing.T }

func (b *rejectReadBody) Read([]byte) (int, error) {
	b.t.Error("oversized declared body should not be read")
	return 0, io.EOF
}

type zeroFileReader struct{}

func (zeroFileReader) Read(p []byte) (int, error) {
	clear(p)
	return len(p), nil
}

func TestFileSpoolStreamingBoundaryWithoutLargeFixture(t *testing.T) {
	spool, err := NewFileSpool(t.TempDir(), 1)
	if err != nil {
		t.Fatal(err)
	}
	const smallLimit = 1024
	exact, err := spool.putLimited(t.Context(), io.LimitReader(zeroFileReader{}, smallLimit), smallLimit)
	if err != nil || exact.Stored || exact.Size != smallLimit || len(exact.SHA256) != 64 {
		t.Fatal("exact streaming limit or quota hash fallback failed", exact, err)
	}
	if _, err := spool.putLimited(t.Context(), io.LimitReader(zeroFileReader{}, smallLimit+1), smallLimit); !errors.Is(err, ErrFileTooLarge) {
		t.Fatal("streaming overflow accepted", err)
	}
	entries, err := os.ReadDir(spool.dir)
	if err != nil || len(entries) != 1 || entries[0].Name() != ".lock" {
		t.Fatal("bounded failed upload left file data", entries, err)
	}
}

func TestFileNativeHandoffCannotRepeatAndReconcilesAfterRestart(t *testing.T) {
	s, r, h := fileService(t)
	if w := uploadFile(t, h, "file-native-1", "a.txt", "file body", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if items, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession}); err != nil || len(items) != 0 {
		t.Fatal("legacy leased file", items, err)
	}
	item := filePoll(t, s, r)
	if item.LeaseToken == "" {
		t.Fatal("missing task token")
	}
	for _, operation := range []string{"download", "renew"} {
		if _, err := s.FileTask(t.Context(), fileOp(r, item, operation)); err != nil {
			t.Fatal(err)
		}
	}
	wrong := fileOp(r, item, "begin")
	wrong.SHA256 = strings.Repeat("0", 64)
	if _, err := s.FileTask(t.Context(), wrong); !errors.Is(err, ErrFileConflict) {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	grants := make(chan bool, 2)
	for range 2 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := s.FileTask(t.Context(), fileOp(r, item, "begin"))
			if err != nil {
				t.Error(err)
			}
			grants <- result.Granted
		}()
	}
	wg.Wait()
	close(grants)
	count := 0
	for granted := range grants {
		if granted {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("grants=%d", count)
	}
	if items, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: 1}); err != nil || len(items) != 0 {
		t.Fatal("native task re-leased", items, err)
	}
	ack := ModuleAckRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, Items: []ModuleAckItem{{ID: item.ID, LeaseToken: item.LeaseToken, Status: "unknown", ChatRecordID: 42, Error: "native result unknown"}}}
	if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 1 || items[0].Status != "unknown" {
		t.Fatal(items, err)
	}
	if items, err := s.outbox.(OutboxStatusReader).ListOutboxStatuses(t.Context(), OutboxStatusFilter{Device: item.Device, OwnerWxID: item.OwnerWxID, Status: "failed"}); err != nil || len(items) != 1 || items[0].Status != "unknown" {
		t.Fatal("failed/unconfirmed filter hid unknown", items, err)
	}
	if err := s.CleanupFiles(t.Context()); err != nil {
		t.Fatal(err)
	}
	if f, err := s.fileSpool.Open(item.Attachment.ID); err != nil {
		t.Fatal("unknown bytes deleted", err)
	} else {
		f.Close()
	}
	old := r
	r = accountRegistration(r.WxID, 2)
	if _, err := s.RegisterModule(t.Context(), r); err != nil {
		t.Fatal(err)
	}
	if _, err := s.FileTask(t.Context(), fileOp(old, item, "status")); !errors.Is(err, ErrAccountSession) {
		t.Fatal("old session accepted", err)
	}
	status, err := s.FileTask(t.Context(), fileOp(r, item, "status"))
	if err != nil || status.Item.Status != "unknown" {
		t.Fatal(status, err)
	}
	ack.AccountSession = r.AccountSession
	ack.Items[0].Status = "sent"
	ack.Items[0].Error = ""
	if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 1 || items[0].Status != "sent" {
		t.Fatal(items, err)
	}
	if len(s.Hub().Recent(100)) != 0 {
		t.Fatal("file ACK fabricated chat content instead of relying on the native observation")
	}
	before := len(s.Hub().Recent(100))
	if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 1 || !items[0].AckReplay {
		t.Fatal(items, err)
	}
	if len(s.Hub().Recent(100)) != before {
		t.Fatal("ACK replay published duplicate")
	}
	if err = s.CleanupFiles(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err = s.fileSpool.Open(item.Attachment.ID); err == nil {
		t.Fatal("terminal bytes retained")
	}
	if w := uploadFile(t, h, "file-native-1", "a.txt", "file body", 1, true); w.Code != 200 {
		t.Fatal("tombstone lost", w.Code)
	}
}

func TestFileExpiryStaleLeaseAndInstallationIsolation(t *testing.T) {
	s, r, h := fileService(t)
	w := uploadFile(t, h, "file-expiry-1", "a.txt", "file body", 1, true)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	item := filePoll(t, s, r)
	o := s.outbox.(*MemoryOutbox)
	o.mu.Lock()
	o.items[0].leaseUntil = time.Now().Add(-time.Second)
	o.mu.Unlock()
	renewed := filePoll(t, s, r)
	if renewed.LeaseToken == item.LeaseToken {
		t.Fatal("lease token reused")
	}
	if _, err := s.FileTask(t.Context(), fileOp(r, item, "begin")); !errors.Is(err, ErrFileConflict) {
		t.Fatal("stale begin", err)
	}
	ack := ModuleAckRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, Items: []ModuleAckItem{{ID: item.ID, LeaseToken: item.LeaseToken, Status: "failed"}}}
	if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 0 {
		t.Fatal("stale ack", items, err)
	}
	// Expiry and begin serialize on the same row/mutex. After expiry, no grant.
	o.mu.Lock()
	o.items[0].Attachment.ExpiresAt = time.Now().Add(-time.Second).UTC().Format(time.RFC3339)
	o.mu.Unlock()
	if err := s.CleanupFiles(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err := s.FileTask(t.Context(), fileOp(r, renewed, "begin")); !errors.Is(err, ErrFileConflict) {
		t.Fatal("expired grant", err)
	}
	other := accountRegistration(r.WxID, 3)
	other.InstanceID = "installation-00002"
	other.Takeover = TakeoverForeground
	if _, err := s.RegisterModule(t.Context(), other); err != nil {
		t.Fatal(err)
	}
	if _, err := s.FileTask(t.Context(), fileOp(other, item, "status")); !errors.Is(err, ErrFileConflict) {
		t.Fatal("other installation read file", err)
	}
}

func TestFileDownloadHTTPAuthAndPrivateSpool(t *testing.T) {
	s, r, h := fileService(t)
	if w := uploadFile(t, h, "file-dl-key", "a.txt", "actual file bytes", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	item := filePoll(t, s, r)
	request := func(op FileOperation) *httptest.ResponseRecorder {
		payload, _ := json.Marshal(op)
		req := httptest.NewRequest("POST", "/module/outbox/file/download", bytes.NewReader(payload))
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}
	valid := fileOp(r, item, "download")
	got := request(valid)
	if got.Code != 200 || got.Body.String() != "actual file bytes" || got.Header().Get("X-Content-SHA256") != item.Attachment.SHA256 {
		t.Fatal(got.Code, got.Body.String())
	}
	valid.APIKey = "wrong"
	if got = request(valid); got.Code == 200 {
		t.Fatal("unauthenticated bytes")
	}
	valid = fileOp(r, item, "download")
	valid.LeaseToken = "wrong"
	if got = request(valid); got.Code != 409 {
		t.Fatal(got.Code)
	}
	valid = fileOp(r, item, "download")
	valid.WxID = "other"
	if got = request(valid); got.Code == 200 {
		t.Fatal("cross-owner bytes")
	}
	for _, path := range []string{"/api/media/" + item.Attachment.ID, "/files/" + item.Attachment.ID} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", path, nil))
		if strings.Contains(rec.Body.String(), "actual file bytes") {
			t.Fatal("public file exposure")
		}
	}
	info, err := os.Stat(filepath.Join(s.fileSpool.dir, item.Attachment.ID))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("non-private blob", info, err)
	}
}

func TestFileSpoolOrphansAndQuota(t *testing.T) {
	s, err := NewFileSpool(t.TempDir(), 10)
	if err != nil {
		t.Fatal(err)
	}
	one, err := s.Put(t.Context(), strings.NewReader("123456"))
	if err != nil || !one.Stored {
		t.Fatal(one, err)
	}
	two, err := s.Put(t.Context(), strings.NewReader("123456"))
	if err != nil || two.Stored || two.SHA256 != one.SHA256 {
		t.Fatal(two, err)
	}
	old := time.Now().Add(-2 * time.Hour)
	_ = os.Chtimes(filepath.Join(s.dir, one.ID), old, old)
	if err = s.SweepOrphans(t.Context(), func(context.Context, string) (bool, error) { return true, nil }); err != nil {
		t.Fatal(err)
	}
	f, err := s.Open(one.ID)
	if err != nil {
		t.Fatal("referenced file removed")
	}
	f.Close()
	if err = s.SweepOrphans(t.Context(), func(context.Context, string) (bool, error) { return false, nil }); err != nil {
		t.Fatal(err)
	}
	if _, err = s.Open(one.ID); err == nil {
		t.Fatal("orphan not removed")
	}
	if _, err = s.Open("../../etc/passwd"); err == nil {
		t.Fatal("path traversal")
	}
}

func TestFileHTTPAckReleasesWebSocketInFlight(t *testing.T) {
	s, r, h := fileService(t)
	s.pollEvery = 20 * time.Millisecond
	if w := uploadFile(t, h, "file-ws-key", "a.txt", "bytes", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	server := httptest.NewServer(h)
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key="+r.APIKey+"&wxid="+r.WxID+"&account_session="+r.AccountSession+"&file_protocol=1")
	defer conn.close()
	file := readTestWSMessageOfType(t, conn, "outbox").Items[0]
	if result, err := s.FileTask(t.Context(), fileOp(r, file, "begin")); err != nil || !result.Granted {
		t.Fatal(result, err)
	}
	_, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", OwnerWxID: r.WxID, WxIDs: []string{"filehelper"}, Text: "after file"})
	if err != nil {
		t.Fatal(err)
	}
	ack := ModuleAckRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, Items: []ModuleAckItem{{ID: file.ID, LeaseToken: file.LeaseToken, Status: "unknown", ChatRecordID: 42}}}
	if items, err := s.AckOutbox(t.Context(), ack); err != nil || len(items) != 1 {
		t.Fatal(items, err)
	}
	next := readTestWSMessageOfType(t, conn, "outbox")
	if len(next.Items) != 1 || next.Items[0].Text != "after file" {
		t.Fatal(next)
	}
	third, err := s.SendText(t.Context(), SendTextRequest{Device: "phone-a", OwnerWxID: r.WxID, WxIDs: []string{"filehelper"}, Text: "still queued"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.AckOutbox(t.Context(), ack); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond)
	view, found, err := s.outbox.(*MemoryOutbox).OutboxStatusByID(t.Context(), third, "phone-a", r.WxID)
	if err != nil || !found || view.AttemptCount != 0 {
		t.Fatal("old file ACK released another task", view, err)
	}
}

func TestExpiredUnstartedFileReleasesWebSocketSlot(t *testing.T) {
	s, r, h := fileService(t)
	s.pollEvery = 20 * time.Millisecond
	if w := uploadFile(t, h, "file-expired-ws", "a.txt", "bytes", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	server := httptest.NewServer(h)
	defer server.Close()
	conn := dialTestWebSocket(t, server.URL, "/module/outbox/ws?api_key="+r.APIKey+"&wxid="+r.WxID+"&account_session="+r.AccountSession+"&file_protocol=1")
	defer conn.close()
	first := readTestWSMessageOfType(t, conn, "outbox").Items[0]
	o := s.outbox.(*MemoryOutbox)
	o.mu.Lock()
	o.items[0].leaseUntil = time.Now().Add(-time.Second)
	o.mu.Unlock()
	next := readTestWSMessageOfType(t, conn, "outbox").Items[0]
	if next.ID != first.ID || next.LeaseToken == first.LeaseToken || next.AttemptCount != 2 {
		t.Fatal("expired file slot stalled or reused token", next)
	}
	if _, err := s.FileTask(t.Context(), fileOp(r, first, "begin")); !errors.Is(err, ErrFileConflict) {
		t.Fatal("old grant accepted", err)
	}
}
