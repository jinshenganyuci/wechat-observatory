package bridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image"
	"image/png"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testPNG(t *testing.T) []byte {
	t.Helper()
	var data bytes.Buffer
	if err := png.Encode(&data, image.NewRGBA(image.Rect(0, 0, 3, 2))); err != nil {
		t.Fatal(err)
	}
	return data.Bytes()
}

func imageUpload(t *testing.T, h http.Handler, key, name string, data []byte) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	for k, v := range map[string]string{"device": "phone-a", "owner_wxid": "wxid_self", "account_generation": "1", "wxid": "filehelper"} {
		if err := form.WriteField(k, v); err != nil {
			t.Fatal(err)
		}
	}
	part, err := form.CreateFormFile("file", name)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write(data)
	_ = form.Close()
	req := httptest.NewRequest("POST", "/api/send/image", &body)
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Idempotency-Key", key)
	req.Header.Set("X-Bridge-Password", "admin")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func TestImageUsesAttachmentGrantIdempotencyAndCleanup(t *testing.T) {
	s, r, handler := fileService(t)
	png := testPNG(t)
	response := imageUpload(t, handler, "image-request-1", "图片.png", png)
	if response.Code != 200 {
		t.Fatal(response.Code, response.Body.String())
	}
	var result struct {
		ID     int64 `json:"outbox_id"`
		Replay bool  `json:"idempotent_replay"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	id := result.ID
	response = imageUpload(t, handler, "image-request-1", "图片.png", png)
	_ = json.Unmarshal(response.Body.Bytes(), &result)
	if response.Code != 200 || result.ID != id || !result.Replay {
		t.Fatal("image idempotency", response.Body.String())
	}
	for _, protocol := range []int{0, 1} {
		items, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: protocol})
		if err != nil || len(items) != 0 {
			t.Fatal("old phone received picture", protocol, items, err)
		}
	}
	items, err := s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: 2})
	if err != nil || len(items) != 1 || items[0].Kind != "image" || items[0].LeaseToken == "" {
		t.Fatal(items, err)
	}
	item := items[0]
	view, found, err := s.outbox.(FileMaintenanceStore).FileTaskViewByID(t.Context(), id, item.Device, item.OwnerWxID)
	if err != nil || !found || view.Kind != "image" || view.CanCancelUnstarted || view.CanCleanupServer {
		t.Fatal("active image incorrectly cleanable", view, err)
	}
	grant, err := s.FileTask(t.Context(), fileOp(r, item, "begin"))
	if err != nil || !grant.Granted {
		t.Fatal(grant, err)
	}
	repeated, err := s.FileTask(t.Context(), fileOp(r, item, "begin"))
	if err != nil || repeated.Granted {
		t.Fatal("second native permission", repeated, err)
	}
	ack := ModuleAckRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, Items: []ModuleAckItem{{ID: id, LeaseToken: item.LeaseToken, Status: "unknown", ChatRecordID: 567}}}
	if got, e := s.AckOutbox(t.Context(), ack); e != nil || len(got) != 1 {
		t.Fatal(got, e)
	}
	if err = s.CleanupFiles(t.Context()); err != nil {
		t.Fatal(err)
	}
	f, err := s.fileSpool.Open(item.Attachment.ID)
	if err != nil {
		t.Fatal("uncertain image removed automatically", err)
	}
	f.Close()
	ack.Items[0].Status = "sent"
	if got, e := s.AckOutbox(t.Context(), ack); e != nil || len(got) != 1 {
		t.Fatal(got, e)
	}
	if len(s.Hub().Recent(10)) != 0 {
		t.Fatal("image ACK was forged as a text observation")
	}
	if err = s.CleanupFiles(t.Context()); err != nil {
		t.Fatal(err)
	}
	if f, e := s.fileSpool.Open(item.Attachment.ID); e == nil {
		f.Close()
		t.Fatal("sent image retained")
	}
	if w := uploadFile(t, handler, "file-after-image", "document.txt", "file", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	items, err = s.PollOutbox(t.Context(), ModulePollRequest{APIKey: r.APIKey, WxID: r.WxID, AccountSession: r.AccountSession, FileProtocol: 2})
	if err != nil || len(items) != 1 || items[0].Kind != "file" {
		t.Fatal("new image phone lost document support", items, err)
	}
}

func TestImageRequestValidationAndFileCompatibility(t *testing.T) {
	s, _, handler := fileService(t)
	png := testPNG(t)
	for _, name := range []string{"image.bin", "image", "image.gif"} {
		if w := imageUpload(t, handler, "unsupported-image-name", name, png); w.Code != 400 {
			t.Fatal("unsupported native image filename accepted", name, w.Code)
		}
	}
	if w := imageUpload(t, handler, "truncated-image-1", "broken.png", png[:len(png)-12]); w.Code != 400 {
		t.Fatal("truncated PNG accepted", w.Code)
	}
	if w := imageUpload(t, handler, "invalid-image-1", "fake.png", []byte("not an image")); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := imageUpload(t, handler, "oversize-image-1", "big.png", make([]byte, MaxImageBytes+1)); w.Code != 413 {
		t.Fatal(w.Code)
	}
	if w := uploadFile(t, handler, "ordinary-file-1", "report.txt", "body", 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	memory := s.outbox.(*MemoryOutbox)
	var oldFingerprint string
	for _, f := range memory.files {
		oldFingerprint = f.request.Fingerprint
	}
	fingerprint, _ := json.Marshal([]any{"filehelper", int64(1), "report.txt", int64(4), hashFileKey("body")})
	if oldFingerprint != hashFileKey(string(fingerprint)) {
		t.Fatal("old document fingerprint changed")
	}
	if w := uploadFile(t, handler, "same-kind-key-1", "image.dat", string(png), 1, true); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := imageUpload(t, handler, "same-kind-key-1", "image.png", png); w.Code != 409 {
		t.Fatal("image replayed ordinary document", w.Code, w.Body.String())
	}
}

type imageDedupePersistence struct {
	fakePersistence
	event MessageEvent
}

func (p *imageDedupePersistence) InboundEventByKey(_ context.Context, key string) (MessageEvent, bool, error) {
	return p.event, p.event.EventKey == key, nil
}

func (p *imageDedupePersistence) RecordInboundEvent(_ context.Context, e MessageEvent) (MessageEvent, error) {
	if p.event.EventKey == e.EventKey {
		return p.event, nil
	}
	p.event = e
	return e, nil
}

func TestIncomingImagePrivateRetrievalReplayAndExpiry(t *testing.T) {
	p := &imageDedupePersistence{}
	s := newTestService("", WithPersistence(p))
	spool, err := NewFileSpool(t.TempDir(), DefaultImageSpoolBytes)
	if err != nil {
		t.Fatal(err)
	}
	s.imageSpool = spool
	data := testPNG(t)
	spool.quota = int64(len(data)) // Exactly full after the first successful ingest.
	event := MessageEvent{APIKey: testAPIKey, ID: "img1", Device: "phone-a", From: "wxid_friend", To: "wxid_self", Text: "[图片]", Direction: DirectionRecv, MessageType: 3, MediaKind: "image", MediaBase64: base64.StdEncoding.EncodeToString(data), MediaURL: "https://untrusted.invalid/private", MediaName: "图片.png", CreateTime: 123456789}
	for range 2 {
		if _, err = s.Ingest(t.Context(), event); err != nil {
			t.Fatal(err)
		}
	}
	if p.event.MediaBase64 != "" || p.event.MediaMime != "image/png" || p.event.MediaSize != int64(len(data)) || inboundImageID(p.event.MediaURL) == "" {
		t.Fatal(p.event)
	}
	entries, _ := os.ReadDir(spool.dir)
	count := 0
	for _, entry := range entries {
		if blobIDPattern.MatchString(entry.Name()) {
			count++
		}
	}
	if count != 1 {
		t.Fatal("duplicate ingestion retained new blob", count)
	}
	h := NewHTTPServer(s, "admin").Handler()
	get := func(auth bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest("GET", p.event.MediaURL, nil)
		if auth {
			req.Header.Set("X-Bridge-Password", "admin")
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		return w
	}
	if err = os.Rename(spool.dir, spool.dir+"-unavailable"); err != nil {
		t.Fatal(err)
	}
	if w := get(true); w.Code != 503 {
		t.Fatal("temporary storage failure was terminal", w.Code)
	}
	if err = os.Rename(spool.dir+"-unavailable", spool.dir); err != nil {
		t.Fatal(err)
	}
	if w := get(false); w.Code != 401 {
		t.Fatal("public picture download", w.Code)
	}
	w := get(true)
	digest := sha256.Sum256(data)
	if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), data) || w.Header().Get("X-Content-SHA256") != hex.EncodeToString(digest[:]) || w.Header().Get("Content-Type") != "image/png" {
		t.Fatal(w.Code, w.Header())
	}
	old := time.Now().Add(-25 * time.Hour)
	if err = os.Chtimes(filepath.Join(spool.dir, inboundImageID(p.event.MediaURL)), old, old); err != nil {
		t.Fatal(err)
	}
	if w = get(true); w.Code != 410 {
		t.Fatal("expired image still available", w.Code)
	}
	if err = s.CleanupImages(t.Context()); err != nil {
		t.Fatal(err)
	}
	if w = get(true); w.Code != 404 {
		t.Fatal("expired image retained", w.Code)
	}
}

func TestIncomingImageFailuresAndNestedSpoolIsolation(t *testing.T) {
	parent, err := NewFileSpool(t.TempDir(), 100)
	if err != nil {
		t.Fatal(err)
	}
	child, err := NewFileSpool(filepath.Join(parent.dir, "inbound-images"), 1)
	if err != nil {
		t.Fatal(err)
	}
	s := newTestService("")
	s.imageSpool = child
	event := MessageEvent{MediaKind: "image", MediaBase64: base64.StdEncoding.EncodeToString(testPNG(t)), MediaURL: "https://do-not-fetch.invalid"}
	stored, err := s.StoreMediaAttachment(t.Context(), event)
	if !errors.Is(err, ErrFileQuota) || stored.MediaURL != "" || stored.MediaBase64 != "" {
		t.Fatal(stored, err)
	}
	blob, err := parent.Put(t.Context(), bytes.NewReader([]byte("file")))
	if err != nil || !blob.Stored {
		t.Fatal("child image directory broke file upload", blob, err)
	}
	if err = parent.SweepOrphans(t.Context(), func(context.Context, string) (bool, error) { return false, nil }); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(child.dir); err != nil {
		t.Fatal("outgoing cleaner touched incoming directory", err)
	}
	f, err := parent.Open(blob.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if b, _ := io.ReadAll(f); string(b) != "file" {
		t.Fatal(string(b))
	}
}

func TestIncomingStorageFailureDoesNotPublishOrAdvance(t *testing.T) {
	s := newTestService("")
	spool, err := NewFileSpool(t.TempDir(), 1)
	if err != nil {
		t.Fatal(err)
	}
	s.imageSpool = spool
	e := MessageEvent{APIKey: testAPIKey, ID: "quota-image", Device: "phone-a", From: "wxid_friend", To: "wxid_self", Text: "[图片]", Direction: DirectionRecv, MessageType: 3, MediaKind: "image", MediaBase64: base64.StdEncoding.EncodeToString(testPNG(t))}
	result, err := s.Ingest(t.Context(), e)
	var failure *IngestPersistenceError
	if result != nil || !errors.As(err, &failure) || len(s.Hub().Recent(1)) != 0 {
		t.Fatal("quota failure consumed event", result, err)
	}
}
