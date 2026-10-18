package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var ErrInvalidFile = errors.New("invalid file request")
var ErrUnsupportedFileType = errors.New("image and video files are not supported by this file sender")

type FileAttachment struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	ExpiresAt string `json:"expires_at"`
}
type FileEnqueue struct {
	Device, OwnerWxID, WxID, IdempotencyHash, Fingerprint string
	Attachment                                            FileAttachment
	Stored                                                bool
	AllowNew                                              bool
}
type FileOperation struct {
	APIKey         string `json:"api_key"`
	Device         string `json:"device"`
	WxID           string `json:"wxid"`
	AccountSession string `json:"account_session"`
	ID             int64  `json:"id"`
	LeaseToken     string `json:"lease_token"`
	SHA256         string `json:"sha256,omitempty"`
	InstanceID     string `json:"-"`
	CredentialID   string `json:"-"`
	AuthVersion    int64  `json:"-"`
	Operation      string `json:"-"`
}
type FileTaskResult struct {
	OK           bool             `json:"ok"`
	Granted      bool             `json:"granted"`
	LeaseExpired bool             `json:"lease_expired,omitempty"`
	Item         ModuleOutboxItem `json:"item"`
}

const fileTransferTimeout = 20 * time.Minute

type FileOutbox interface {
	EnqueueFile(context.Context, FileEnqueue) (ModuleOutboxItem, bool, error)
	OperateFile(context.Context, FileOperation) (FileTaskResult, error)
	FileCleanupCandidates(context.Context) ([]string, error)
	MarkFileRemoved(context.Context, string) error
	FileBlobReferenced(context.Context, string) (bool, error)
}

func validateFileName(name string) error {
	if !utf8.ValidString(name) || len(name) > 240 || name == "" || name == "." || name == ".." || strings.TrimSpace(name) != name || strings.ContainsAny(name, "/\\") {
		return ErrInvalidFile
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return ErrInvalidFile
		}
	}
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))
	for _, blocked := range strings.Fields("jpg jpeg bmp png tif tiff heic heif webp avi flv m4v mkv mov mp4 mts webm wmv") {
		if ext == blocked {
			return ErrUnsupportedFileType
		}
	}
	return nil
}
func hashFileKey(text string) string {
	digest := sha256.Sum256([]byte(text))
	return hex.EncodeToString(digest[:])
}

func (s *HTTPServer) sendFile(w http.ResponseWriter, r *http.Request) {
	if s.service.fileSpool == nil {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	key := r.Header.Get("Idempotency-Key")
	if len(key) < 8 || len(key) > 128 {
		writeFileError(w, ErrInvalidFile)
		return
	}
	for _, c := range key {
		if c < 33 || c > 126 {
			writeFileError(w, ErrInvalidFile)
			return
		}
	}
	const maxRequestBytes = MaxFileBytes + (64 << 10)
	if r.ContentLength > maxRequestBytes {
		writeFileError(w, ErrFileTooLarge)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), fileTransferTimeout)
	defer cancel()
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(fileTransferTimeout))
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBytes)
	reader, err := r.MultipartReader()
	if err != nil {
		writeFileError(w, ErrInvalidFile)
		return
	}
	fields := map[string]string{}
	var blob FileBlob
	var name string
	retained := false
	seenFile := false
	defer func() {
		if blob.Stored && !retained {
			_ = s.service.fileSpool.Remove(blob.ID)
		}
	}()
	for {
		part, e := reader.NextPart()
		if e == io.EOF {
			break
		}
		if e != nil {
			writeFileError(w, ErrInvalidFile)
			return
		}
		if part.FormName() == "file" {
			if seenFile {
				writeFileError(w, ErrInvalidFile)
				return
			}
			seenFile = true
			name = part.FileName()
			if e = validateFileName(name); e != nil {
				writeFileError(w, e)
				return
			}
			blob, e = s.service.fileSpool.Put(ctx, part)
			if e != nil {
				writeFileError(w, e)
				return
			}
		} else {
			field := part.FormName()
			if field != "device" && field != "owner_wxid" && field != "account_generation" && field != "wxid" {
				writeFileError(w, ErrInvalidFile)
				return
			}
			if _, exists := fields[field]; exists || part.FileName() != "" {
				writeFileError(w, ErrInvalidFile)
				return
			}
			value, e := io.ReadAll(io.LimitReader(part, 1025))
			if e != nil || len(value) > 1024 {
				writeFileError(w, ErrInvalidFile)
				return
			}
			fields[field] = string(value)
		}
		_ = part.Close()
	}
	generation, err := strconv.ParseInt(fields["account_generation"], 10, 64)
	if err != nil || generation <= 0 || !seenFile || len(fields) != 4 {
		writeFileError(w, ErrInvalidFile)
		return
	}
	req := FileEnqueue{Device: strings.TrimSpace(fields["device"]), OwnerWxID: strings.TrimSpace(fields["owner_wxid"]), WxID: strings.TrimSpace(fields["wxid"]), IdempotencyHash: hashFileKey(key), Stored: blob.Stored,
		Attachment: FileAttachment{ID: blob.ID, Name: name, Size: blob.Size, SHA256: blob.SHA256, ExpiresAt: s.service.now().Add(24 * time.Hour).UTC().Format(time.RFC3339)}}
	if req.Device == "" || req.OwnerWxID == "" || req.WxID == "" || len(req.Device) > 128 || len(req.OwnerWxID) > 191 || len(req.WxID) > 191 {
		writeFileError(w, ErrInvalidFile)
		return
	}
	fingerprint, _ := json.Marshal([]any{req.WxID, generation, name, blob.Size, blob.SHA256})
	req.Fingerprint = hashFileKey(string(fingerprint))
	// A lost SQL COMMIT response is ambiguous. Preserve bytes until the DB-backed
	// orphan sweep can prove they are unreferenced; never break a committed task.
	retained = true
	item, replay, err := s.service.EnqueueFile(ctx, req, generation)
	if err != nil {
		if errors.Is(err, ErrAccountSession) || errors.Is(err, ErrIdempotencyConflict) || errors.Is(err, ErrFileQuota) {
			retained = false
		}
		writeFileError(w, err)
		return
	}
	retained = !replay
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "outbox_id": item.ID, "idempotent_replay": replay})
}

func (s *Service) EnqueueFile(ctx context.Context, req FileEnqueue, generation int64) (item ModuleOutboxItem, replay bool, err error) {
	store, ok := s.outbox.(FileOutbox)
	if !ok {
		return item, false, ErrFileUnavailable
	}
	err = s.withAccountLock(ctx, req.Device, func(ctx context.Context) error {
		binding, found, e := s.currentBinding(ctx, req.Device)
		if e != nil {
			return e
		}
		// The store checks a pre-existing key before generation/quota admission so
		// a lost successful response remains recoverable after bytes are removed.
		req.AllowNew = found && binding.OwnerWxID == req.OwnerWxID && binding.Epoch == generation
		item, replay, e = store.EnqueueFile(ctx, req)
		return e
	})
	if err == nil && !replay {
		s.notifyOutbox(req.Device)
	}
	return
}

func (s *Service) FileTask(ctx context.Context, req FileOperation) (result FileTaskResult, err error) {
	store, ok := s.outbox.(FileOutbox)
	if !ok {
		return result, ErrFileUnavailable
	}
	if req.ID <= 0 || req.AccountSession == "" {
		return result, ErrInvalidFile
	}
	err = s.withModuleAccount(ctx, req.APIKey, req.WxID, req.AccountSession, false, func(ctx context.Context, owner string) error {
		auth, e := s.authorizeModuleAPIKey(ctx, req.APIKey)
		if e != nil {
			return e
		}
		binding, found, e := s.currentBinding(ctx, auth.Device)
		if e != nil {
			return e
		}
		if !found || binding.InstanceID == "" || req.Device != "" && req.Device != auth.Device {
			return ErrAccountSession
		}
		req.Device, req.WxID, req.InstanceID = auth.Device, owner, binding.InstanceID
		req.CredentialID, req.AuthVersion = binding.CredentialID, binding.AuthVersion
		result, e = store.OperateFile(ctx, req)
		return e
	})
	if err == nil && req.Operation == "abandon" {
		s.notifyOutbox(result.Item.Device)
	}
	return
}

func (s *HTTPServer) moduleFile(w http.ResponseWriter, r *http.Request) {
	var req FileOperation
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&req); err != nil {
		writeFileError(w, ErrInvalidFile)
		return
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		writeFileError(w, ErrInvalidFile)
		return
	}
	req.Operation = r.PathValue("operation")
	if req.Operation != "download" && req.Operation != "renew" && req.Operation != "begin" && req.Operation != "status" && req.Operation != "abandon" {
		writeFileError(w, ErrInvalidFile)
		return
	}
	result, err := s.service.FileTask(r.Context(), req)
	if err != nil {
		writeFileError(w, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	if req.Operation != "download" {
		writeJSON(w, http.StatusOK, result)
		return
	}
	if s.service.fileSpool == nil || result.Item.Attachment == nil {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	f, err := s.service.fileSpool.Open(result.Item.Attachment.ID)
	if err != nil {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() != result.Item.Attachment.Size {
		writeFileError(w, ErrFileUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
	w.Header().Set("X-Content-SHA256", result.Item.Attachment.SHA256)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(fileTransferTimeout))
	_, _ = io.Copy(w, f)
}

func writeFileError(w http.ResponseWriter, err error) {
	status, code, message := http.StatusServiceUnavailable, "file_storage_unavailable", "file storage is unavailable"
	var maxBytesError *http.MaxBytesError
	switch {
	case errors.Is(err, ErrInvalidFile):
		status, code, message = 400, "invalid_request", err.Error()
	case errors.Is(err, ErrUnsupportedFileType):
		status, code, message = 400, "unsupported_file_type", err.Error()
	case errors.Is(err, ErrFileTooLarge), errors.As(err, &maxBytesError):
		status, code, message = 413, "file_too_large", ErrFileTooLarge.Error()
	case errors.Is(err, ErrFileQuota):
		status, code, message = 507, "file_quota_exceeded", err.Error()
	case errors.Is(err, ErrIdempotencyConflict):
		status, code, message = 409, "idempotency_conflict", err.Error()
	case errors.Is(err, ErrFileConflict):
		status, code, message = 409, "file_task_conflict", err.Error()
	case errors.Is(err, ErrAccountSession):
		status, code, message = 409, "account_session_conflict", "account session changed"
	case isModuleAuthError(err):
		status, code, message = 401, "unauthorized", "module authentication failed"
	}
	writeError(w, status, code, message)
}

func (s *Service) CleanupFiles(ctx context.Context) error {
	store, ok := s.outbox.(FileOutbox)
	if !ok || s.fileSpool == nil {
		return nil
	}
	ids, err := store.FileCleanupCandidates(ctx)
	if err != nil {
		return err
	}
	for _, id := range ids {
		if err = s.fileSpool.Remove(id); err != nil && !os.IsNotExist(err) {
			return err
		}
		if err = store.MarkFileRemoved(ctx, id); err != nil {
			return err
		}
	}
	return s.fileSpool.SweepOrphans(ctx, store.FileBlobReferenced)
}
