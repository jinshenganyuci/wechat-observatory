package bridge

import (
	"context"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// OutboxStatusItem is a read-only view. LeaseUntil is a receipt lease, never an
// estimate of when WeChat or its recipient will receive a message.
type OutboxStatusItem struct {
	ModuleOutboxItem
	LeaseUntil string `json:"lease_until,omitempty"`
}

type OutboxStatusFilter struct {
	Device    string
	OwnerWxID string
	WxID      string
	Status    string
	Limit     int
}

func (f OutboxStatusFilter) Normalize() (OutboxStatusFilter, error) {
	f.Device = strings.TrimSpace(f.Device)
	f.OwnerWxID = strings.TrimSpace(f.OwnerWxID)
	f.WxID = strings.TrimSpace(f.WxID)
	if f.Device == "" || f.OwnerWxID == "" {
		return f, errors.New("device and owner_wxid are required")
	}
	if f.Status == "" {
		f.Status = "active"
	}
	switch f.Status {
	case "active", "failed", "all":
	default:
		return f, errors.New("status must be active, failed or all")
	}
	if f.Limit <= 0 {
		f.Limit = 50
	}
	if f.Limit > 100 {
		f.Limit = 100
	}
	return f, nil
}

type OutboxStatusReader interface {
	ListOutboxStatuses(context.Context, OutboxStatusFilter) ([]OutboxStatusItem, error)
}

func (s *HTTPServer) outboxStatuses(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := 0
	if raw := q.Get("limit"); raw != "" {
		var err error
		limit, err = strconv.Atoi(raw)
		if err != nil || limit <= 0 {
			writeError(w, http.StatusBadRequest, "invalid_request", "limit must be a positive integer")
			return
		}
	}
	filter, err := (OutboxStatusFilter{Device: q.Get("device"), OwnerWxID: q.Get("owner_wxid"),
		WxID: q.Get("wxid"), Status: q.Get("status"), Limit: limit}).Normalize()
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error())
		return
	}
	reader, ok := s.service.outbox.(OutboxStatusReader)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "outbox_read_unavailable", "outbox status reader is unavailable")
		return
	}
	items, err := reader.ListOutboxStatuses(r.Context(), filter)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "outbox_read_failed", "could not read message delivery status")
		return
	}
	if items == nil {
		items = []OutboxStatusItem{}
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "server_time": s.service.now().UTC().Format(time.RFC3339Nano)})
}

func (o *MemoryOutbox) ListOutboxStatuses(_ context.Context, filter OutboxStatusFilter) ([]OutboxStatusItem, error) {
	filter, err := filter.Normalize()
	if err != nil {
		return nil, err
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	items := []OutboxStatusItem{}
	for _, item := range o.items {
		if item.Device != filter.Device || item.OwnerWxID != filter.OwnerWxID || (filter.WxID != "" && item.WxID != filter.WxID) {
			continue
		}
		if filter.Status == "active" && item.Status != "pending" && item.Status != "leased" {
			continue
		}
		if filter.Status == "failed" && item.Status != "failed" {
			continue
		}
		items = append(items, OutboxStatusItem{ModuleOutboxItem: item.ModuleOutboxItem, LeaseUntil: formatRFC3339(item.leaseUntil)})
	}
	sort.Slice(items, func(i, j int) bool {
		if filter.Status == "active" {
			return items[i].ID < items[j].ID
		}
		return items[i].ID > items[j].ID
	})
	if len(items) > filter.Limit {
		items = items[:filter.Limit]
	}
	return items, nil
}
