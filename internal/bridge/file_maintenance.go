package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

var ErrFileTaskNotFound = errors.New("file task not found")

// This admin DTO deliberately excludes credentials and the module lease token.
type FileTaskView struct {
	ID                   int64          `json:"id"`
	Device               string         `json:"device"`
	OwnerWxID            string         `json:"owner_wxid"`
	WxID                 string         `json:"wxid"`
	Status               string         `json:"status"`
	AttemptCount         int            `json:"attempt_count"`
	ChatRecordID         int64          `json:"chat_record_id"`
	LastError            string         `json:"last_error"`
	CreatedAt            string         `json:"created_at"`
	UpdatedAt            string         `json:"updated_at"`
	LeaseUntil           string         `json:"lease_until,omitempty"`
	NativeStartedAt      string         `json:"native_started_at,omitempty"`
	Attachment           FileAttachment `json:"attachment"`
	ServerCopy           FileServerCopy `json:"server_copy"`
	PhoneCopyState       string         `json:"phone_copy_state"`
	CanCleanupServer     bool           `json:"can_cleanup_server"`
	CanCancelUnstarted   bool           `json:"can_cancel_unstarted"`
	CleanupBlockedReason string         `json:"cleanup_blocked_reason"`
}

type FileServerCopy struct {
	State              string `json:"state"`
	CleanupRequestedAt string `json:"cleanup_requested_at,omitempty"`
	UpdatedAt          string `json:"updated_at,omitempty"`
	LastError          string `json:"last_error,omitempty"`
}

// The native timestamp proves a one-time grant, not an actual native call or
// upload. No existing phone protocol reports whether its staging copy remains.
func (v *FileTaskView) SetCleanupPermissions(now time.Time) {
	v.PhoneCopyState = "not_reported"
	v.CanCleanupServer, v.CanCancelUnstarted = false, false
	v.CleanupBlockedReason = ""
	if v.ServerCopy.State == "removed" {
		v.CleanupBlockedReason = "already_removed"
		return
	}
	if v.ServerCopy.State == "cleanup_pending" {
		// Retrying an already committed cleanup request cannot grant a new
		// cancellation or native send, even if the previous unlink failed.
		v.CanCleanupServer = true
		v.CleanupBlockedReason = "cleanup_pending"
		return
	}
	switch v.Status {
	case "sent", "failed", "cancelled":
		v.CanCleanupServer = true
	case "native_started", "unknown":
		if v.NativeStartedAt != "" {
			v.CanCleanupServer = true
		} else {
			v.CleanupBlockedReason = "missing_native_grant"
		}
	case "pending", "leased":
		if v.NativeStartedAt != "" {
			v.CleanupBlockedReason = "unsupported_state"
			return
		}
		lease, err := time.Parse(time.RFC3339Nano, v.LeaseUntil)
		if v.Status == "leased" && v.LeaseUntil != "" && (err != nil || lease.After(now)) {
			v.CleanupBlockedReason = "active_lease"
			return
		}
		v.CanCancelUnstarted = true
		v.CleanupBlockedReason = "unstarted_task"
	default:
		v.CleanupBlockedReason = "unsupported_state"
	}
}

type FileTaskFilter struct {
	Device, OwnerWxID, Status string
	BeforeID                  int64
	Limit                     int
}

func (f FileTaskFilter) Normalize() (FileTaskFilter, error) {
	f.Device, f.OwnerWxID = strings.TrimSpace(f.Device), strings.TrimSpace(f.OwnerWxID)
	if f.Device == "" || f.OwnerWxID == "" || len(f.Device) > 128 || len(f.OwnerWxID) > 191 || f.BeforeID < 0 || f.Limit < 0 {
		return f, ErrInvalidFile
	}
	if f.Status == "" {
		f.Status = "retained"
	}
	if f.Status != "retained" && f.Status != "unconfirmed" && f.Status != "all" {
		return f, ErrInvalidFile
	}
	if f.Limit == 0 {
		f.Limit = 50
	}
	if f.Limit > 100 {
		f.Limit = 100
	}
	return f, nil
}

type FileCleanupRequest struct {
	Device    string `json:"device"`
	OwnerWxID string `json:"owner_wxid"`
	Mode      string `json:"mode"`
	ID        int64  `json:"-"`
}
type FileMaintenanceStore interface {
	ListFileTasks(context.Context, FileTaskFilter) ([]FileTaskView, error) // limit+1 for keyset pagination
	FileTaskViewByID(context.Context, int64, string, string) (FileTaskView, bool, error)
	RequestFileCleanup(context.Context, FileCleanupRequest) (FileTaskView, error)
	RecordFileCleanupFailure(context.Context, string, string) error
}

func (s *HTTPServer) fileTasks(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := FileTaskFilter{Device: q.Get("device"), OwnerWxID: q.Get("owner_wxid"), Status: q.Get("status")}
	var err error
	if raw := q.Get("before_id"); raw != "" {
		f.BeforeID, err = strconv.ParseInt(raw, 10, 64)
		if err != nil || f.BeforeID <= 0 {
			writeFileError(w, ErrInvalidFile)
			return
		}
	}
	if raw := q.Get("limit"); raw != "" {
		f.Limit, err = strconv.Atoi(raw)
		if err != nil || f.Limit <= 0 {
			writeFileError(w, ErrInvalidFile)
			return
		}
	}
	f, err = f.Normalize()
	if err != nil {
		writeFileError(w, err)
		return
	}
	store, ok := s.service.outbox.(FileMaintenanceStore)
	if !ok {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	items, err := store.ListFileTasks(r.Context(), f)
	if err != nil {
		writeFileError(w, err)
		return
	}
	var next *int64
	if len(items) > f.Limit {
		items = items[:f.Limit]
		id := items[len(items)-1].ID
		next = &id
	}
	if items == nil {
		items = []FileTaskView{}
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 200, map[string]any{"items": items, "next_before_id": next, "server_time": s.service.now().UTC().Format(time.RFC3339Nano)})
}
func (s *HTTPServer) fileTaskDetail(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	f, e := (FileTaskFilter{Device: r.URL.Query().Get("device"), OwnerWxID: r.URL.Query().Get("owner_wxid")}).Normalize()
	if err != nil || id <= 0 || e != nil {
		writeFileError(w, ErrInvalidFile)
		return
	}
	store, ok := s.service.outbox.(FileMaintenanceStore)
	if !ok {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	item, found, err := store.FileTaskViewByID(r.Context(), id, f.Device, f.OwnerWxID)
	if err != nil {
		writeFileError(w, err)
		return
	}
	if !found {
		writeFileError(w, ErrFileTaskNotFound)
		return
	}
	s.writeFileTaskView(w, 200, item)
}
func (s *HTTPServer) cleanupFileTask(w http.ResponseWriter, r *http.Request) {
	var req FileCleanupRequest
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&req); err != nil {
		writeFileError(w, ErrInvalidFile)
		return
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		writeFileError(w, ErrInvalidFile)
		return
	}
	var err error
	req.ID, err = strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeFileError(w, ErrInvalidFile)
		return
	}
	item, err := s.service.CleanupFileTask(r.Context(), req)
	if err != nil {
		writeFileError(w, err)
		return
	}
	status := http.StatusAccepted
	if item.ServerCopy.State == "removed" {
		status = http.StatusOK
	}
	s.writeFileTaskView(w, status, item)
}
func (s *HTTPServer) writeFileTaskView(w http.ResponseWriter, status int, item FileTaskView) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, status, map[string]any{"item": item, "server_time": s.service.now().UTC().Format(time.RFC3339Nano)})
}

func (s *Service) CleanupFileTask(ctx context.Context, req FileCleanupRequest) (item FileTaskView, err error) {
	f, e := (FileTaskFilter{Device: req.Device, OwnerWxID: req.OwnerWxID}).Normalize()
	if e != nil || req.ID <= 0 || (req.Mode != "server_only" && req.Mode != "cancel_unstarted") {
		return item, ErrInvalidFile
	}
	req.Device, req.OwnerWxID = f.Device, f.OwnerWxID
	store, ok := s.outbox.(FileMaintenanceStore)
	if !ok {
		return item, ErrFileUnavailable
	}
	files, ok := s.outbox.(FileOutbox)
	if !ok {
		return item, ErrFileUnavailable
	}
	err = s.withAccountLock(ctx, req.Device, func(ctx context.Context) error { var e error; item, e = store.RequestFileCleanup(ctx, req); return e })
	if err != nil || item.ServerCopy.State == "removed" {
		return
	}
	if req.Mode == "cancel_unstarted" {
		s.notifyOutbox(req.Device)
	}
	// The durable request commits before filesystem work. Failures are retried
	// by the existing sweeper; they never retry or change a native send.
	_ = s.removeFileBytes(ctx, files, item.Attachment.ID)
	var found bool
	item, found, err = store.FileTaskViewByID(ctx, req.ID, req.Device, req.OwnerWxID)
	if err == nil && !found {
		err = ErrFileTaskNotFound
	}
	return
}

func (s *Service) removeFileBytes(ctx context.Context, store FileOutbox, id string) error {
	code := ""
	var err error
	if s.fileSpool == nil {
		code, err = "storage_unavailable", ErrFileUnavailable
	} else if err = s.fileSpool.Remove(id); err != nil {
		code = "remove_failed"
	} else if err = store.MarkFileRemoved(ctx, id); err != nil {
		code = "record_failed"
	}
	if code != "" {
		if maintenance, ok := store.(FileMaintenanceStore); ok {
			err = errors.Join(err, maintenance.RecordFileCleanupFailure(ctx, id, code))
		}
	}
	return err
}
