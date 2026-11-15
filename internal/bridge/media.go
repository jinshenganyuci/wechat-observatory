package bridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	MaxImageBytes          int64 = 8 << 20
	MaxImagePixels         int64 = 32_000_000
	DefaultImageSpoolBytes int64 = 256 << 20
	inboundImageTTL              = 24 * time.Hour
	imageURLPrefix               = "/api/media/images/"
)

var (
	ErrImageTooLarge = errors.New("image exceeds 8 MiB")
	ErrInvalidImage  = errors.New("image must have a .jpg, .jpeg or .png name and contain JPEG or PNG, at most 32 megapixels and 16384 pixels per side")
)

// Check dimensions before full decoding so malformed/truncated payloads never
// reach native WeChat or vision. Extensions and MIME declarations are untrusted.
func inspectImage(data []byte) (string, error) {
	if int64(len(data)) > MaxImageBytes {
		return "", ErrImageTooLarge
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || (format != "jpeg" && format != "png") || cfg.Width <= 0 || cfg.Height <= 0 || cfg.Width > 16384 || cfg.Height > 16384 || int64(cfg.Width)*int64(cfg.Height) > MaxImagePixels {
		return "", ErrInvalidImage
	}
	if _, decodedFormat, err := image.Decode(bytes.NewReader(data)); err != nil || decodedFormat != format {
		return "", ErrInvalidImage
	}
	return "image/" + format, nil
}

func validateImageName(name string) error {
	if err := validateAttachmentName(name); err != nil {
		return err
	}
	switch strings.ToLower(filepath.Ext(name)) {
	case ".jpg", ".jpeg", ".png":
		return nil
	default:
		return ErrInvalidImage
	}
}

func inboundImageID(url string) string {
	id := strings.TrimPrefix(url, imageURLPrefix)
	if url != imageURLPrefix+id || !blobIDPattern.MatchString(id) {
		return ""
	}
	return id
}

// Incoming pictures use their own spool, quota and short retention; they never
// become outgoing tasks. All URLs are server-owned, never fetched from a phone.
func (s *Service) StoreMediaAttachment(ctx context.Context, event MessageEvent) (MessageEvent, error) {
	encoded := event.MediaBase64
	event.MediaBase64, event.MediaURL = "", ""
	if event.MediaKind != "image" || encoded == "" {
		return event, nil
	}
	if s.imageSpool == nil {
		return event, nil // Compatibility for explicitly metadata-only service instances.
	}
	if int64(len(encoded)) > ((MaxImageBytes+2)/3)*4 {
		return event, ErrImageTooLarge
	}
	data, err := io.ReadAll(io.LimitReader(base64.NewDecoder(base64.StdEncoding, strings.NewReader(encoded)), MaxImageBytes+1))
	if err != nil {
		return event, ErrInvalidImage
	}
	mime, err := inspectImage(data)
	if err != nil {
		return event, err
	}
	event.MediaMime, event.MediaSize = mime, int64(len(data))
	if validateImageName(event.MediaName) != nil {
		event.MediaName = "image." + strings.TrimPrefix(mime, "image/")
	}
	// Resolve a lost successful response before reserving more bytes. A full
	// spool must not prevent acknowledgement of a previously persisted image.
	key, err := s.messageEventKey(event)
	if err != nil {
		return event, err
	}
	if reader, ok := s.persistence.(InboundEventKeyReader); ok {
		previous, found, err := reader.InboundEventByKey(ctx, key)
		if err != nil {
			return event, err
		}
		if found && inboundImageID(previous.MediaURL) != "" {
			return previous, nil
		}
	} else if s.persistence == nil {
		// Metadata-only deployments already retain a bounded recent-event ring.
		for _, previous := range s.hub.Recent(500) {
			if previous.EventKey == key && inboundImageID(previous.MediaURL) != "" {
				return previous, nil
			}
		}
	}
	blob, err := s.imageSpool.putLimited(ctx, bytes.NewReader(data), MaxImageBytes)
	if err != nil {
		return event, err
	}
	if !blob.Stored {
		return event, ErrFileQuota
	}
	event.MediaURL = imageURLPrefix + blob.ID
	return event, nil
}

func (s *HTTPServer) incomingImage(w http.ResponseWriter, r *http.Request) {
	spool := s.service.imageSpool
	id := r.PathValue("id")
	if spool == nil || !blobIDPattern.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	// A missing/unreadable mount is a retryable storage failure, not an expired
	// attachment. Consumers treat 404/410 as terminal and advance their cursor.
	rootInfo, err := os.Stat(spool.dir)
	if err != nil || !rootInfo.IsDir() {
		writeError(w, http.StatusServiceUnavailable, "image_unavailable", "image storage is unavailable")
		return
	}
	f, err := spool.Open(id)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			http.NotFound(w, r)
		} else {
			writeError(w, http.StatusServiceUnavailable, "image_unavailable", "image storage is unavailable")
		}
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "image_unavailable", "image storage is unavailable")
		return
	}
	w.Header().Set("Cache-Control", "private, no-store")
	if !info.ModTime().Add(inboundImageTTL).After(s.service.now()) {
		writeError(w, http.StatusGone, "image_expired", "incoming image expired after 24 hours")
		return
	}
	data, err := io.ReadAll(io.LimitReader(f, MaxImageBytes+1))
	if err != nil {
		writeError(w, 500, "image_unavailable", "image read failed")
		return
	}
	mime, err := inspectImage(data)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	digest := sha256.Sum256(data)
	w.Header().Set("Content-Type", mime)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Content-SHA256", hex.EncodeToString(digest[:]))
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	_, _ = w.Write(data)
}

func (s *Service) CleanupImages(ctx context.Context) error {
	if s.imageSpool == nil {
		return nil
	}
	lock, err := s.imageSpool.lock(ctx)
	if err != nil {
		return err
	}
	defer unlockSpool(lock)
	entries, err := os.ReadDir(s.imageSpool.dir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return err
		}
		name := entry.Name()
		part := strings.HasPrefix(name, ".part-") && blobIDPattern.MatchString(strings.TrimPrefix(name, ".part-"))
		if !blobIDPattern.MatchString(name) && !part {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		age := inboundImageTTL
		if part {
			age = time.Hour
		}
		if !info.Mode().IsRegular() || info.ModTime().Add(age).After(s.now()) {
			continue
		}
		if err := os.Remove(filepath.Join(s.imageSpool.dir, name)); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}
