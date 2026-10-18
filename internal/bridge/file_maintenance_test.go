package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func fileAdminRequest(h http.Handler, method, path, body string, authenticated bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if authenticated {
		r.Header.Set("X-Bridge-Password", "admin")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func cleanupRequest(item ModuleOutboxItem, mode string) FileCleanupRequest {
	return FileCleanupRequest{ID: item.ID, Device: item.Device, OwnerWxID: item.OwnerWxID, Mode: mode}
}
func cleanupHTTP(h http.Handler, id int64, mode string) *httptest.ResponseRecorder {
	return fileAdminRequest(h, "POST", fmt.Sprintf("/api/files/%d/cleanup", id), `{"device":"phone-a","owner_wxid":"wxid_self","mode":"`+mode+`"}`, true)
}
func TestFileMaintenanceUnknownRemovalPreservesResultAndRecovery(t *testing.T) {
	s, r, h := fileService(t)
	if w := uploadFile(t, h, "maintenance-key", "test.txt", "original content", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	item := filePoll(t, s, r)
	if result, err := s.FileTask(t.Context(), fileOp(r, item, "begin")); err != nil || !result.Granted {
		t.Fatal(result, err)
	}
	ack := ModuleAckRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, Items: []ModuleAckItem{{ID: item.ID, LeaseToken: item.LeaseToken, Status: "unknown", ChatRecordID: 42, Error: "result not confirmed"}}}
	if got, err := s.AckOutbox(t.Context(), ack); err != nil || len(got) != 1 {
		t.Fatal(got, err)
	}
	before, _, _ := s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), item.ID, item.Device, item.OwnerWxID)
	if !before.CanCleanupServer || before.PhoneCopyState != "not_reported" {
		t.Fatal(before)
	}
	if w := cleanupHTTP(h, item.ID, "server_only"); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	} else if strings.Contains(w.Body.String(), "lease_token") {
		t.Fatal("admin leaked token")
	}
	after, _, _ := s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), item.ID, item.Device, item.OwnerWxID)
	if after.Status != "unknown" || after.LastError != before.LastError || after.UpdatedAt != before.UpdatedAt || after.NativeStartedAt != before.NativeStartedAt || after.ServerCopy.State != "removed" || after.PhoneCopyState != "not_reported" {
		t.Fatal(after)
	}
	if _, err := os.Stat(filepath.Join(s.fileSpool.dir, item.Attachment.ID)); !os.IsNotExist(err) {
		t.Fatal(err)
	}
	if status, err := s.FileTask(t.Context(), fileOp(r, item, "status")); err != nil || status.Item.LeaseToken != item.LeaseToken || status.Item.Attachment.ID != item.Attachment.ID {
		t.Fatal(status, err)
	}
	if _, err := s.FileTask(t.Context(), fileOp(r, item, "download")); !errors.Is(err, ErrFileConflict) {
		t.Fatal("download after grant/removal", err)
	}
	if got, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: 1}); err != nil || len(got) != 0 {
		t.Fatal("re-leased", got, err)
	}
	listed, err := s.outbox.(FileMaintenanceStore).ListFileTasks(t.Context(), FileTaskFilter{Device: item.Device, OwnerWxID: item.OwnerWxID, Status: "unconfirmed"})
	if err != nil || len(listed) != 1 {
		t.Fatal("removed unknown hidden", listed, err)
	}
	listed, err = s.outbox.(FileMaintenanceStore).ListFileTasks(t.Context(), FileTaskFilter{Device: item.Device, OwnerWxID: item.OwnerWxID})
	if err != nil || len(listed) != 0 {
		t.Fatal(listed, err)
	}
	if w := uploadFile(t, h, "maintenance-key", "test.txt", "original content", 1, true); w.Code != 200 || !strings.Contains(w.Body.String(), `"idempotent_replay":true`) {
		t.Fatal("dedup lost", w.Code, w.Body.String())
	}
	ack.Items[0].Status = "sent"
	ack.Items[0].Error = ""
	if got, err := s.AckOutbox(t.Context(), ack); err != nil || len(got) != 1 || got[0].Status != "sent" {
		t.Fatal("late recovery broken", got, err)
	}
	if w := cleanupHTTP(h, item.ID, "server_only"); w.Code != 200 {
		t.Fatal("cleanup not idempotent", w.Code)
	}
}

func TestFileMaintenancePaginationAuthAndScope(t *testing.T) {
	s, _, h := fileService(t)
	for i := 0; i < 55; i++ {
		if w := uploadFile(t, h, fmt.Sprintf("page-key-%03d", i), "a.txt", "x", 1, true); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
	path := "/api/files?device=phone-a&owner_wxid=wxid_self"
	if w := fileAdminRequest(h, "GET", path, "", false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	var first struct {
		Items []FileTaskView `json:"items"`
		Next  *int64         `json:"next_before_id"`
	}
	w := fileAdminRequest(h, "GET", path, "", true)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	if err := json.Unmarshal(w.Body.Bytes(), &first); err != nil || len(first.Items) != 50 || first.Next == nil {
		t.Fatal(first, err)
	}
	var second struct {
		Items []FileTaskView `json:"items"`
		Next  *int64         `json:"next_before_id"`
	}
	w = fileAdminRequest(h, "GET", path+fmt.Sprintf("&before_id=%d", *first.Next), "", true)
	if err := json.Unmarshal(w.Body.Bytes(), &second); err != nil || len(second.Items) != 5 || second.Next != nil || second.Items[0].ID >= *first.Next {
		t.Fatal(second, err)
	}
	id := first.Items[0].ID
	for _, scope := range []string{"device=other&owner_wxid=wxid_self", "device=phone-a&owner_wxid=other"} {
		if w := fileAdminRequest(h, "GET", fmt.Sprintf("/api/files/%d?", id)+scope, "", true); w.Code != 404 {
			t.Fatal(w.Code)
		}
	}
	for _, body := range []string{`{"device":"other","owner_wxid":"wxid_self","mode":"cancel_unstarted"}`, `{"device":"phone-a","owner_wxid":"other","mode":"cancel_unstarted"}`} {
		if w := fileAdminRequest(h, "POST", fmt.Sprintf("/api/files/%d/cleanup", id), body, true); w.Code != 404 {
			t.Fatal(w.Code)
		}
	}
	if w := fileAdminRequest(h, "POST", fmt.Sprintf("/api/files/%d/cleanup", id), `{}`, false); w.Code != 401 {
		t.Fatal(w.Code)
	}
	for _, suffix := range []string{"&status=invalid", "&before_id=-1", "&limit=0", "&limit=no"} {
		if w := fileAdminRequest(h, "GET", path+suffix, "", true); w.Code != 400 {
			t.Fatal(w.Code)
		}
	}
	if w := cleanupHTTP(h, id, "server_only"); w.Code != 409 {
		t.Fatal("deleted queued bytes", w.Code)
	}
	if w := cleanupHTTP(h, id, "cancel_unstarted"); w.Code != 200 {
		t.Fatal(w.Code)
	}
	view, _, _ := s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), id, "phone-a", "wxid_self")
	if view.Status != "cancelled" || view.ServerCopy.State != "removed" {
		t.Fatal(view)
	}
}

func TestFileMaintenanceLeaseGuardsSerializeWithBeginAndRenew(t *testing.T) {
	for _, operation := range []string{"begin", "renew"} {
		for _, expired := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/expired=%v", operation, expired), func(t *testing.T) {
				s, r, h := fileService(t)
				if w := uploadFile(t, h, "race-cleanup", "a.txt", "x", 1, true); w.Code != 200 {
					t.Fatal(w.Code)
				}
				item := filePoll(t, s, r)
				outbox := s.outbox.(*MemoryOutbox)
				if expired {
					outbox.mu.Lock()
					outbox.items[0].leaseUntil = outbox.now().Add(-time.Second)
					outbox.mu.Unlock()
				}
				var wg sync.WaitGroup
				wg.Add(2)
				var operationErr, cleanupErr error
				go func() { defer wg.Done(); _, operationErr = s.FileTask(t.Context(), fileOp(r, item, operation)) }()
				go func() {
					defer wg.Done()
					_, cleanupErr = s.CleanupFileTask(t.Context(), cleanupRequest(item, "cancel_unstarted"))
				}()
				wg.Wait()
				if expired {
					if cleanupErr != nil || !errors.Is(operationErr, ErrFileConflict) {
						t.Fatal(operationErr, cleanupErr)
					}
				} else {
					if operationErr != nil || !errors.Is(cleanupErr, ErrFileConflict) {
						t.Fatal(operationErr, cleanupErr)
					}
				}
				status, err := s.FileTask(t.Context(), fileOp(r, item, "status"))
				if err != nil || status.Item.LeaseToken != item.LeaseToken {
					t.Fatal("token lost", status, err)
				}
				if expired {
					if status.Item.Status != "cancelled" {
						t.Fatal(status)
					}
				} else if _, err := s.fileSpool.Open(item.Attachment.ID); err != nil {
					t.Fatal("removed active source", err)
				}
			})
		}
	}
}

func TestFileMaintenanceFailureIsDurableRetryAndDoesNotChangeNativeState(t *testing.T) {
	for _, automatic := range []bool{false, true} {
		t.Run(fmt.Sprint(automatic), func(t *testing.T) {
			s, r, h := fileService(t)
			if w := uploadFile(t, h, "cleanup-failure", "a.txt", "bytes", 1, true); w.Code != 200 {
				t.Fatal(w.Code)
			}
			item := filePoll(t, s, r)
			if _, err := s.FileTask(t.Context(), fileOp(r, item, "begin")); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(s.fileSpool.dir, item.Attachment.ID)
			if err := os.Remove(path); err != nil {
				t.Fatal(err)
			}
			if err := os.Mkdir(path, 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(path, "block"), []byte("x"), 0600); err != nil {
				t.Fatal(err)
			}
			w := cleanupHTTP(h, item.ID, "server_only")
			if w.Code != 202 {
				t.Fatal(w.Code, w.Body.String())
			}
			view, _, _ := s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), item.ID, item.Device, item.OwnerWxID)
			if view.ServerCopy.State != "cleanup_pending" || view.ServerCopy.LastError != "remove_failed" || !view.CanCleanupServer || view.Status != "native_started" || view.PhoneCopyState != "not_reported" {
				t.Fatal(view)
			}
			if err := os.Remove(filepath.Join(path, "block")); err != nil {
				t.Fatal(err)
			}
			if err := os.Remove(path); err != nil {
				t.Fatal(err)
			}
			if automatic {
				if err := s.CleanupFiles(t.Context()); err != nil {
					t.Fatal(err)
				}
			} else {
				if w := cleanupHTTP(h, item.ID, "server_only"); w.Code != 200 {
					t.Fatal(w.Code)
				}
			}
			view, _, _ = s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), item.ID, item.Device, item.OwnerWxID)
			if view.ServerCopy.State != "removed" || view.ServerCopy.LastError != "" || view.Status != "native_started" {
				t.Fatal(view)
			}
		})
	}
}

func TestFileMaintenanceUnknownWithoutGrantCannotDelete(t *testing.T) {
	v := FileTaskView{Status: "unknown", ServerCopy: FileServerCopy{State: "retained"}}
	v.SetCleanupPermissions(time.Now())
	if v.CanCleanupServer || v.CanCancelUnstarted || v.CleanupBlockedReason != "missing_native_grant" {
		t.Fatal(v)
	}
}

// A lost DB response after unlink leaves a durable request, not permission to
// send again. Repeating the original cancellation mode only finishes cleanup.
type failFileRemovalRecord struct {
	*MemoryOutbox
	fail bool
}

func (s *failFileRemovalRecord) MarkFileRemoved(ctx context.Context, id string) error {
	if s.fail {
		return errors.New("injected record failure")
	}
	return s.MemoryOutbox.MarkFileRemoved(ctx, id)
}
func TestFileMaintenanceRecordFailureAndOriginalRequestReplay(t *testing.T) {
	s, _, h := fileService(t)
	if w := uploadFile(t, h, "record-failure", "a.txt", "body", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	store := &failFileRemovalRecord{MemoryOutbox: s.outbox.(*MemoryOutbox), fail: true}
	s.outbox = store
	item := store.items[0].ModuleOutboxItem
	view, err := s.CleanupFileTask(t.Context(), cleanupRequest(item, "cancel_unstarted"))
	if err != nil || view.Status != "cancelled" || view.ServerCopy.State != "cleanup_pending" || view.ServerCopy.LastError != "record_failed" || !view.CanCleanupServer {
		t.Fatal(view, err)
	}
	if _, err = os.Stat(filepath.Join(s.fileSpool.dir, item.Attachment.ID)); !os.IsNotExist(err) {
		t.Fatal(err)
	}
	store.fail = false
	view, err = s.CleanupFileTask(t.Context(), cleanupRequest(item, "cancel_unstarted"))
	if err != nil || view.Status != "cancelled" || view.ServerCopy.State != "removed" {
		t.Fatal(view, err)
	}
}

func TestFileMaintenanceSweeperContinuesAfterBrokenBlob(t *testing.T) {
	s, _, h := fileService(t)
	var items []ModuleOutboxItem
	for i := 0; i < 2; i++ {
		if w := uploadFile(t, h, fmt.Sprintf("sweeper-key-%d", i), "a.txt", "body", 1, true); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
	store := s.outbox.(*MemoryOutbox)
	for _, item := range store.items {
		items = append(items, item.ModuleOutboxItem)
	}
	for _, item := range items {
		if _, err := store.RequestFileCleanup(t.Context(), cleanupRequest(item, "cancel_unstarted")); err != nil {
			t.Fatal(err)
		}
	}
	broken := filepath.Join(s.fileSpool.dir, items[0].Attachment.ID)
	if err := os.Remove(broken); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(broken, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(broken, "block"), []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := s.CleanupFiles(t.Context()); err == nil {
		t.Fatal("expected first blob error")
	}
	view, _, err := store.FileTaskViewByID(t.Context(), items[1].ID, items[1].Device, items[1].OwnerWxID)
	if err != nil || view.ServerCopy.State != "removed" {
		t.Fatal("one failure blocked another cleanup", view, err)
	}
}
