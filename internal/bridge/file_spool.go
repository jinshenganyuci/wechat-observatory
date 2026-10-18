package bridge

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

const MaxFileBytes int64 = 25 << 20
const DefaultFileSpoolBytes int64 = 512 << 20

var (
	ErrFileTooLarge        = errors.New("file exceeds 25 MiB")
	ErrFileQuota           = errors.New("file storage quota exceeded")
	ErrFileUnavailable     = errors.New("file storage is unavailable")
	ErrFileConflict        = errors.New("file task or lease is no longer valid")
	ErrIdempotencyConflict = errors.New("idempotency key was already used for a different file request")
	blobIDPattern          = regexp.MustCompile(`^[a-f0-9]{32}$`)
)

// FileSpool contains only server-owned blobs, never user-supplied filesystem paths.
// Replicas using one database MUST share this directory (including its flock).
type FileSpool struct {
	dir   string
	quota int64
}
type FileBlob struct {
	ID, SHA256 string
	Size       int64
	Stored     bool
}

func NewFileSpool(directory string, quota int64) (*FileSpool, error) {
	if directory == "" {
		return nil, ErrFileUnavailable
	}
	if quota <= 0 {
		quota = DefaultFileSpoolBytes
	}
	abs, err := filepath.Abs(directory)
	if err != nil {
		return nil, err
	}
	if err = os.MkdirAll(abs, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(abs)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, ErrFileUnavailable
	}
	if err = os.Chmod(abs, 0700); err != nil {
		return nil, err
	}
	return &FileSpool{dir: abs, quota: quota}, nil
}

func randomFileID() (string, error) {
	var b [16]byte
	_, err := rand.Read(b[:])
	return hex.EncodeToString(b[:]), err
}

func (s *FileSpool) lock(ctx context.Context) (*os.File, error) {
	f, err := os.OpenFile(filepath.Join(s.dir, ".lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	for {
		var busy bool
		if busy, err = trySpoolLock(f); err == nil && !busy {
			return f, nil
		}
		if err != nil {
			f.Close()
			return nil, err
		}
		select {
		case <-ctx.Done():
			f.Close()
			return nil, ctx.Err()
		case <-time.After(25 * time.Millisecond):
		}
	}
}
func unlockSpool(f *os.File) { releaseSpoolLock(f); _ = f.Close() }
func syncDir(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

// Put hashes a bounded stream even when quota prevents storing it. This allows
// an identical idempotent retry to resolve its tombstone without allocating bytes.
func (s *FileSpool) Put(ctx context.Context, input io.Reader) (blob FileBlob, err error) {
	lock, err := s.lock(ctx)
	if err != nil {
		return blob, err
	}
	defer unlockSpool(lock)
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return blob, err
	}
	var used int64
	for _, entry := range entries {
		if entry.Name() == ".lock" {
			continue
		}
		info, e := entry.Info()
		if e != nil {
			return blob, e
		}
		if !info.Mode().IsRegular() {
			return blob, ErrFileUnavailable
		}
		used += info.Size()
	}
	blob.ID, err = randomFileID()
	if err != nil {
		return blob, err
	}
	path := filepath.Join(s.dir, ".part-"+blob.ID)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return blob, err
	}
	defer func() { f.Close(); _ = os.Remove(path) }()
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	fits := len(entries) < 10000
	for {
		if err := ctx.Err(); err != nil {
			return blob, err
		}
		n, readErr := input.Read(buffer)
		if n > 0 {
			blob.Size += int64(n)
			if blob.Size > MaxFileBytes {
				return blob, ErrFileTooLarge
			}
			_, _ = hash.Write(buffer[:n])
			if used+blob.Size > s.quota {
				fits = false
			}
			if fits {
				if _, err := f.Write(buffer[:n]); err != nil {
					return blob, err
				}
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return blob, readErr
		}
	}
	if blob.Size == 0 {
		return blob, ErrInvalidFile
	}
	blob.SHA256 = hex.EncodeToString(hash.Sum(nil))
	if !fits {
		return blob, nil
	}
	if err = f.Sync(); err != nil {
		return blob, err
	}
	if err = f.Close(); err != nil {
		return blob, err
	}
	if err = os.Rename(path, filepath.Join(s.dir, blob.ID)); err != nil {
		return blob, err
	}
	if err = syncDir(s.dir); err != nil {
		_ = os.Remove(filepath.Join(s.dir, blob.ID))
		return blob, err
	}
	blob.Stored = true
	return blob, nil
}

func (s *FileSpool) Open(id string) (*os.File, error) {
	if !blobIDPattern.MatchString(id) {
		return nil, ErrFileUnavailable
	}
	path := filepath.Join(s.dir, id)
	before, err := os.Lstat(path)
	if err != nil || !before.Mode().IsRegular() {
		return nil, ErrFileUnavailable
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, ErrFileUnavailable
	}
	after, err := f.Stat()
	if err != nil || !os.SameFile(before, after) {
		f.Close()
		return nil, ErrFileUnavailable
	}
	return f, nil
}

func (s *FileSpool) Remove(id string) error {
	if !blobIDPattern.MatchString(id) {
		return ErrFileUnavailable
	}
	err := os.Remove(filepath.Join(s.dir, id))
	if os.IsNotExist(err) {
		return nil
	}
	return err
}

// Old unreferenced blobs are upload crashes. A generous grace exceeds the HTTP
// upload deadline; live DB metadata always wins, including native-started tasks.
func (s *FileSpool) SweepOrphans(ctx context.Context, referenced func(context.Context, string) (bool, error)) error {
	lock, err := s.lock(ctx)
	if err != nil {
		return err
	}
	defer unlockSpool(lock)
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		name := entry.Name()
		part := len(name) == 38 && name[:6] == ".part-" && blobIDPattern.MatchString(name[6:])
		if !blobIDPattern.MatchString(name) && !part {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() || time.Since(info.ModTime()) < time.Hour {
			continue
		}
		if !part {
			found, e := referenced(ctx, name)
			if e != nil {
				return e
			}
			if found {
				continue
			}
		}
		if err := os.Remove(filepath.Join(s.dir, name)); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}
