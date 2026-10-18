package bridge

import (
	"context"
	"sort"
	"time"
)

func (o *MemoryOutbox) fileTaskView(item memoryOutboxItem, f *memoryFile) FileTaskView {
	v := FileTaskView{ID: item.ID, Device: item.Device, OwnerWxID: item.OwnerWxID, WxID: item.WxID, Status: item.Status,
		AttemptCount: item.AttemptCount, ChatRecordID: item.ChatRecordID, LastError: item.LastError, CreatedAt: item.CreatedAt, UpdatedAt: item.UpdatedAt,
		LeaseUntil: formatRFC3339(item.leaseUntil), NativeStartedAt: item.NativeStartedAt, Attachment: f.request.Attachment,
		ServerCopy: FileServerCopy{State: "retained", CleanupRequestedAt: f.cleanupRequestedAt, UpdatedAt: f.cleanupUpdatedAt, LastError: f.cleanupLastError}}
	if f.removed {
		v.ServerCopy.State = "removed"
	} else if f.cleanupRequestedAt != "" {
		v.ServerCopy.State = "cleanup_pending"
	}
	v.SetCleanupPermissions(o.now())
	return v
}
func (o *MemoryOutbox) ListFileTasks(_ context.Context, filter FileTaskFilter) ([]FileTaskView, error) {
	filter, err := filter.Normalize()
	if err != nil {
		return nil, err
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	views := []FileTaskView{}
	for _, item := range o.items {
		if item.Kind != "file" || item.Device != filter.Device || item.OwnerWxID != filter.OwnerWxID || filter.BeforeID > 0 && item.ID >= filter.BeforeID {
			continue
		}
		for _, f := range o.files {
			if f.outboxID != item.ID {
				continue
			}
			if filter.Status == "retained" && f.removed || filter.Status == "unconfirmed" && item.Status != "unknown" && item.Status != "native_started" {
				break
			}
			views = append(views, o.fileTaskView(item, f))
			break
		}
	}
	sort.Slice(views, func(i, j int) bool { return views[i].ID > views[j].ID })
	if len(views) > filter.Limit+1 {
		views = views[:filter.Limit+1]
	}
	return views, nil
}
func (o *MemoryOutbox) FileTaskViewByID(_ context.Context, id int64, device, owner string) (FileTaskView, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, item := range o.items {
		if item.Kind == "file" && item.ID == id && item.Device == device && item.OwnerWxID == owner {
			for _, f := range o.files {
				if f.outboxID == id {
					return o.fileTaskView(item, f), true, nil
				}
			}
		}
	}
	return FileTaskView{}, false, nil
}
func (o *MemoryOutbox) RequestFileCleanup(_ context.Context, req FileCleanupRequest) (FileTaskView, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	for i := range o.items {
		item := &o.items[i]
		if item.Kind != "file" || item.ID != req.ID || item.Device != req.Device || item.OwnerWxID != req.OwnerWxID {
			continue
		}
		for _, f := range o.files {
			if f.outboxID != item.ID {
				continue
			}
			v := o.fileTaskView(*item, f)
			if f.removed || f.cleanupRequestedAt != "" {
				return v, nil
			}
			if req.Mode == "cancel_unstarted" && v.CanCancelUnstarted {
				item.Status, item.LastError = "cancelled", "cancelled by administrator before native submission"
				item.leaseUntil = time.Time{} // Preserve the original token for phone reconciliation.
				item.UpdatedAt = formatRFC3339(o.now())
			} else if req.Mode != "server_only" || !v.CanCleanupServer {
				return FileTaskView{}, ErrFileConflict
			}
			f.cleanupRequestedAt = formatRFC3339(o.now())
			return o.fileTaskView(*item, f), nil
		}
	}
	return FileTaskView{}, ErrFileTaskNotFound
}
func (o *MemoryOutbox) RecordFileCleanupFailure(_ context.Context, id, code string) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, f := range o.files {
		if f.request.Attachment.ID == id && !f.removed {
			if f.cleanupRequestedAt == "" {
				f.cleanupRequestedAt = formatRFC3339(o.now())
			}
			f.cleanupUpdatedAt, f.cleanupLastError = formatRFC3339(o.now()), code
		}
	}
	return nil
}
