package bridge

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"
)

func TestOutboxStatusReadOnlyAndScope(t *testing.T) {
	o := NewMemoryOutbox()
	o.now = func() time.Time { return time.Date(2026, 10, 17, 0, 0, 0, 0, time.UTC) }
	for _, action := range []ReplyAction{
		{Device: "phone", OwnerWxID: "self", WxID: "friend", Text: "first"},
		{Device: "phone", OwnerWxID: "self", WxID: "other", Text: "second"},
		{Device: "phone", OwnerWxID: "another-account", WxID: "friend", Text: "private"},
		{Device: "another-phone", OwnerWxID: "self", WxID: "friend", Text: "private"},
	} {
		if _, err := o.EnqueueReply(t.Context(), action); err != nil {
			t.Fatal(err)
		}
	}
	filter := OutboxStatusFilter{Device: "phone", OwnerWxID: "self"}
	items, err := o.ListOutboxStatuses(t.Context(), filter)
	if err != nil || len(items) != 2 || items[0].Text != "first" || items[1].Text != "second" {
		t.Fatalf("scoped FIFO: %+v %v", items, err)
	}
	for _, item := range items {
		if item.Status != "pending" || item.AttemptCount != 0 || item.LeaseUntil != "" {
			t.Fatalf("reading changed delivery state: %+v", item)
		}
	}
	leased, err := o.PollReplyActions(t.Context(), ModulePollRequest{Device: "phone", WxID: "self", Limit: 1})
	if err != nil || len(leased) != 1 || leased[0].ID != items[0].ID {
		t.Fatalf("read consumed message: %+v %v", leased, err)
	}
	items, _ = o.ListOutboxStatuses(t.Context(), filter)
	if items[0].LeaseUntil != "2026-10-17T00:01:00Z" || items[0].AttemptCount != 1 || items[0].Status != "leased" {
		t.Fatalf("lease view: %+v", items[0])
	}
	_, err = o.AckReplyActions(t.Context(), ModuleAckRequest{Device: "phone", WxID: "self", Items: []ModuleAckItem{{ID: leased[0].ID, Status: "failed", Error: "test failure"}}})
	if err != nil {
		t.Fatal(err)
	}
	items, _ = o.ListOutboxStatuses(t.Context(), filter)
	if len(items) != 1 || items[0].Text != "second" {
		t.Fatalf("active includes terminal item: %+v", items)
	}
	filter.Status = "failed"
	items, _ = o.ListOutboxStatuses(t.Context(), filter)
	if len(items) != 1 || items[0].Text != "first" || items[0].LastError != "test failure" || items[0].LeaseUntil != "" {
		t.Fatalf("failed view: %+v", items)
	}
	filter.Status, filter.WxID = "all", "other"
	items, _ = o.ListOutboxStatuses(t.Context(), filter)
	if len(items) != 1 || items[0].Text != "second" {
		t.Fatalf("recipient filter: %+v", items)
	}
	filter.WxID, filter.Limit = "", 1
	items, _ = o.ListOutboxStatuses(t.Context(), filter)
	if len(items) != 1 || items[0].Text != "second" {
		t.Fatalf("recent order and limit: %+v", items)
	}
}

func TestOutboxStatusHTTPAuthenticationAndValidation(t *testing.T) {
	o := NewMemoryOutbox()
	_, _ = o.EnqueueReply(t.Context(), ReplyAction{Device: "phone", OwnerWxID: "self", WxID: "friend", Text: "message body"})
	s := NewHTTPServer(NewService(Config{}, WithOutbox(o)), "admin", WithDeviceAdminPassword("device-secret"))
	for _, tc := range []struct {
		name, query, header, secret string
		status                      int
	}{
		{"no authentication", "device=phone&owner_wxid=self", "", "", 401},
		{"device admin cannot read messages", "device=phone&owner_wxid=self", "X-Bridge-Device-Password", "device-secret", 401},
		{"missing account", "device=phone", "X-Bridge-Password", "admin", 400},
		{"missing device", "owner_wxid=self", "X-Bridge-Password", "admin", 400},
		{"invalid status", "device=phone&owner_wxid=self&status=sent", "X-Bridge-Password", "admin", 400},
		{"invalid limit", "device=phone&owner_wxid=self&limit=x", "X-Bridge-Password", "admin", 400},
		{"negative limit", "device=phone&owner_wxid=self&limit=-1", "X-Bridge-Password", "admin", 400},
		{"admin", "device=phone&owner_wxid=self", "X-Bridge-Password", "admin", 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "/api/outbox?"+tc.query, nil)
			if tc.header != "" {
				req.Header.Set(tc.header, tc.secret)
			}
			rec := httptest.NewRecorder()
			s.Handler().ServeHTTP(rec, req)
			if rec.Code != tc.status {
				t.Fatalf("got %d: %s", rec.Code, rec.Body.String())
			}
			if tc.status == 200 {
				var result struct {
					Items      []OutboxStatusItem `json:"items"`
					ServerTime string             `json:"server_time"`
				}
				if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
					t.Fatal(err)
				}
				if len(result.Items) != 1 || result.Items[0].Text != "message body" || rec.Header().Get("Cache-Control") != "no-store" {
					t.Fatalf("bad response: %+v", result)
				}
				if _, err := time.Parse(time.RFC3339Nano, result.ServerTime); err != nil {
					t.Fatalf("invalid server time: %s", result.ServerTime)
				}
			}
		})
	}
	filter, err := (OutboxStatusFilter{Device: "phone", OwnerWxID: "self", Limit: 1000}).Normalize()
	if err != nil || filter.Limit != 100 {
		t.Fatalf("unbounded limit: %+v %v", filter, err)
	}
}
