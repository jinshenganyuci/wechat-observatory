package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
)

var ErrInvalidTextIdempotency = errors.New("idempotent text send requires a visible ASCII key of 8-128 characters, one recipient, and a positive account_generation")
var ErrTextIdempotencyUnavailable = errors.New("idempotent text storage is unavailable")

type IdempotentTextEnqueue struct {
	Device, OwnerWxID, WxID, Text string
	KeyHash, Fingerprint          string
}

type TextIdempotencyStore interface {
	LookupTextIdempotency(context.Context, IdempotentTextEnqueue) (int64, bool, error)
	EnqueueIdempotentText(context.Context, IdempotentTextEnqueue) (int64, bool, error)
}

func textRequestDigest(value []byte) string {
	digest := sha256.Sum256(value)
	return hex.EncodeToString(digest[:])
}

func (s *Service) SendTextIdempotent(ctx context.Context, req SendTextRequest, key string) (id int64, replay bool, err error) {
	if len(key) < 8 || len(key) > 128 || req.AccountGeneration == nil || *req.AccountGeneration <= 0 || len(req.WxIDs) != 1 {
		return 0, false, ErrInvalidTextIdempotency
	}
	for _, c := range key {
		if c < 33 || c > 126 {
			return 0, false, ErrInvalidTextIdempotency
		}
	}
	// Preserve text byte-for-byte just as the existing sender does. Trimming is
	// only an empty-text check. The same normalized recipient is hashed/sent.
	req.Device = firstNonEmpty(strings.TrimSpace(req.Device), s.cfg.DefaultDevice)
	req.WxIDs = append([]string(nil), req.WxIDs...)
	req, err = req.Validate(s.cfg.DefaultDevice)
	if err != nil || req.OwnerWxID == "" || len(req.Device) > 128 || len(req.OwnerWxID) > 191 || len(req.WxIDs[0]) > 191 || strings.Contains(req.WxIDs[0], ",") {
		return 0, false, ErrInvalidTextIdempotency
	}
	store, ok := s.outbox.(TextIdempotencyStore)
	if !ok {
		return 0, false, ErrTextIdempotencyUnavailable
	}
	fingerprint, _ := json.Marshal([]any{*req.AccountGeneration, req.WxIDs[0], req.Text})
	enqueue := IdempotentTextEnqueue{Device: req.Device, OwnerWxID: req.OwnerWxID, WxID: req.WxIDs[0], Text: req.Text, KeyHash: textRequestDigest([]byte(key)), Fingerprint: textRequestDigest(fingerprint)}
	err = s.withAccountLock(ctx, req.Device, func(ctx context.Context) error {
		var e error
		id, replay, e = store.LookupTextIdempotency(ctx, enqueue)
		if e != nil || replay {
			return e
		}
		current, found, e := s.currentBinding(ctx, req.Device)
		if e != nil {
			return e
		}
		if !found || current.Epoch != *req.AccountGeneration || current.OwnerWxID != req.OwnerWxID {
			return ErrAccountSession
		}
		if _, known, e := s.lookupDevice(ctx, req.Device); e != nil {
			return e
		} else if !known {
			return ErrAccountSession
		}
		if req.OwnerWxID != s.deviceWxID(ctx, req.Device) {
			return ErrAccountSession
		}
		if checker, ok := s.persistence.(ModuleLivenessChecker); ok {
			online, e := checker.ModuleOnline(ctx, req.Device, req.OwnerWxID, s.offlineAfter)
			if e != nil {
				return e
			}
			if !online {
				return ErrModuleOffline
			}
		}
		id, replay, e = store.EnqueueIdempotentText(ctx, enqueue)
		return e
	})
	if err == nil && !replay {
		s.notifyOutbox(req.Device)
	}
	return
}

type memoryTextIdempotency struct {
	id          int64
	fingerprint string
}

func (o *MemoryOutbox) LookupTextIdempotency(_ context.Context, req IdempotentTextEnqueue) (int64, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.lookupTextIdempotency(req)
}
func (o *MemoryOutbox) lookupTextIdempotency(req IdempotentTextEnqueue) (int64, bool, error) {
	entry, found := o.textIdempotency[req.Device+"\x00"+req.OwnerWxID+"\x00"+req.KeyHash]
	if !found {
		return 0, false, nil
	}
	if entry.fingerprint != req.Fingerprint {
		return 0, false, ErrIdempotencyConflict
	}
	return entry.id, true, nil
}
func (o *MemoryOutbox) EnqueueIdempotentText(_ context.Context, req IdempotentTextEnqueue) (int64, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if id, found, err := o.lookupTextIdempotency(req); found || err != nil {
		return id, found, err
	}
	item := o.enqueueReplyLocked(ReplyAction{Device: req.Device, OwnerWxID: req.OwnerWxID, WxID: req.WxID, Text: req.Text})
	if o.textIdempotency == nil {
		o.textIdempotency = map[string]memoryTextIdempotency{}
	}
	o.textIdempotency[req.Device+"\x00"+req.OwnerWxID+"\x00"+req.KeyHash] = memoryTextIdempotency{item.ID, req.Fingerprint}
	return item.ID, false, nil
}
