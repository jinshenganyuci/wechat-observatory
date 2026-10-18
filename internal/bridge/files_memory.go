package bridge

import (
	"context"
	"time"
)

type memoryFile struct {
	request                                                FileEnqueue
	outboxID                                               int64
	removed                                                bool
	cleanupRequestedAt, cleanupUpdatedAt, cleanupLastError string
}

func (o *MemoryOutbox) EnqueueFile(_ context.Context, req FileEnqueue) (ModuleOutboxItem, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.files == nil {
		o.files = map[string]*memoryFile{}
	}
	key := req.Device + "\x00" + req.OwnerWxID + "\x00" + req.IdempotencyHash
	if old := o.files[key]; old != nil {
		if old.request.Fingerprint != req.Fingerprint {
			return ModuleOutboxItem{}, false, ErrIdempotencyConflict
		}
		for _, item := range o.items {
			if item.ID == old.outboxID {
				return item.ModuleOutboxItem, true, nil
			}
		}
		return ModuleOutboxItem{ID: old.outboxID, Kind: "file", Attachment: &old.request.Attachment}, true, nil
	}
	if !req.AllowNew {
		return ModuleOutboxItem{}, false, ErrAccountSession
	}
	if !req.Stored {
		return ModuleOutboxItem{}, false, ErrFileQuota
	}
	o.nextID++
	now := formatRFC3339(o.now())
	attachment := req.Attachment
	item := ModuleOutboxItem{ID: o.nextID, Kind: "file", Device: req.Device, OwnerWxID: req.OwnerWxID, WxID: req.WxID, Attachment: &attachment, Status: "pending", CreatedAt: now, UpdatedAt: now}
	o.items = append(o.items, memoryOutboxItem{ModuleOutboxItem: item})
	o.files[key] = &memoryFile{request: req, outboxID: item.ID}
	return item, false, nil
}

func (o *MemoryOutbox) OperateFile(_ context.Context, req FileOperation) (result FileTaskResult, err error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for i := range o.items {
		item := &o.items[i]
		if item.ID != req.ID || item.Kind != "file" {
			continue
		}
		if item.Device != req.Device || item.OwnerWxID != req.WxID || req.InstanceID == "" || item.fileInstance != req.InstanceID {
			return result, ErrFileConflict
		}
		result = FileTaskResult{OK: true, Item: item.ModuleOutboxItem,
			LeaseExpired: item.Status == "leased" && item.NativeStartedAt == "" && !item.leaseUntil.After(o.now())}
		if req.Operation == "status" {
			return result, nil
		}
		if req.LeaseToken == "" || item.LeaseToken != req.LeaseToken {
			return result, ErrFileConflict
		}
		if req.Operation == "begin" && item.NativeStartedAt != "" {
			return result, nil
		}
		if req.Operation == "abandon" && item.Status == "cancelled" && item.NativeStartedAt == "" {
			return result, nil
		}
		expires, _ := time.Parse(time.RFC3339, item.Attachment.ExpiresAt)
		if item.Status != "leased" || item.fileSession != req.AccountSession || !item.leaseUntil.After(o.now()) || !expires.After(o.now()) {
			return result, ErrFileConflict
		}
		switch req.Operation {
		case "download":
		case "renew":
			item.leaseUntil = o.now().Add(defaultOutboxLease)
		case "begin":
			if req.SHA256 != item.Attachment.SHA256 {
				return result, ErrFileConflict
			}
			item.Status = "native_started"
			item.NativeStartedAt = formatRFC3339(o.now())
			item.leaseUntil = time.Time{}
			item.UpdatedAt = formatRFC3339(o.now())
			result.Granted = true
		case "abandon":
			if item.NativeStartedAt != "" {
				return result, ErrFileConflict
			}
			item.Status, item.LastError = "cancelled", "native permission not confirmed; abandoned before send"
			item.leaseUntil = time.Time{}
			item.UpdatedAt = formatRFC3339(o.now())
		default:
			return result, ErrInvalidFile
		}
		result.Item = item.ModuleOutboxItem
		return result, nil
	}
	return result, ErrFileConflict
}

func (o *MemoryOutbox) ackFile(item *memoryOutboxItem, req ModuleAckRequest, ack ModuleAckItem, now time.Time) (ModuleOutboxItem, bool) {
	if item.Device != req.Device || item.OwnerWxID != req.WxID || req.InstanceID == "" || item.fileInstance != req.InstanceID || ack.LeaseToken == "" || item.LeaseToken != ack.LeaseToken {
		return ModuleOutboxItem{}, false
	}
	if item.Status == ack.Status && (item.Status == "sent" || item.Status == "failed" || item.Status == "unknown") && item.ChatRecordID == ack.ChatRecordID && item.LastError == ack.Error {
		replay := item.ModuleOutboxItem
		replay.AckReplay = true
		return replay, true
	}
	started := item.NativeStartedAt != "" && (item.Status == "native_started" || item.Status == "unknown")
	if !started && !(item.Status == "leased" && ack.Status == "failed" && item.fileSession == req.AccountSession && item.leaseUntil.After(now)) {
		return ModuleOutboxItem{}, false
	}
	if ack.Status != "sent" && ack.Status != "failed" && ack.Status != "unknown" {
		return ModuleOutboxItem{}, false
	}
	if ack.Status == "sent" && ack.ChatRecordID <= 0 {
		return ModuleOutboxItem{}, false
	}
	if item.ChatRecordID > 0 && ack.ChatRecordID != item.ChatRecordID {
		return ModuleOutboxItem{}, false
	}
	item.Status, item.LastError = ack.Status, ack.Error
	if ack.ChatRecordID > 0 {
		item.ChatRecordID = ack.ChatRecordID
	}
	item.leaseUntil = time.Time{}
	item.UpdatedAt = formatRFC3339(now)
	return item.ModuleOutboxItem, true
}

func (o *MemoryOutbox) FileCleanupCandidates(_ context.Context) (ids []string, err error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, file := range o.files {
		if file.removed {
			continue
		}
		for i := range o.items {
			item := &o.items[i]
			if item.ID != file.outboxID {
				continue
			}
			expires, _ := time.Parse(time.RFC3339, item.Attachment.ExpiresAt)
			if (item.Status == "pending" || item.Status == "leased") && item.NativeStartedAt == "" && !expires.After(o.now()) {
				item.Status, item.LastError = "cancelled", "file expired before native submission"
				item.leaseUntil = time.Time{}
			}
			if file.cleanupRequestedAt != "" || item.Status == "sent" || item.Status == "failed" || item.Status == "cancelled" {
				ids = append(ids, item.Attachment.ID)
			}
		}
	}
	return
}
func (o *MemoryOutbox) MarkFileRemoved(_ context.Context, id string) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, f := range o.files {
		if f.request.Attachment.ID == id {
			f.removed = true
			f.cleanupUpdatedAt, f.cleanupLastError = formatRFC3339(o.now()), ""
		}
	}
	return nil
}
func (o *MemoryOutbox) FileBlobReferenced(_ context.Context, id string) (bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, f := range o.files {
		if f.request.Attachment.ID == id {
			return true, nil
		}
	}
	return false, nil
}
