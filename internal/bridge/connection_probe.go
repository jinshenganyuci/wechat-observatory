package bridge

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// Legacy configuration screens sent only api_key to /module/diagnostics.
// Reports carry a stage, including an explicitly empty stage. Keep that upload
// contract and its rate limiting separate from the authenticated read-only probe.
func (s *HTTPServer) moduleDiagnosticOrConnectionProbe(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, diagnosticUploadMaxBytes)
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	var fields map[string]json.RawMessage
	if err != nil || json.Unmarshal(body, &fields) != nil || fields == nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid diagnostic request")
		return
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	for name := range fields {
		if strings.EqualFold(name, "stage") {
			s.uploadModuleDiagnostic(w, r)
			return
		}
	}
	s.moduleConnectionProbe(w, r)
}

// moduleConnectionProbe authenticates without registering, refreshing a heartbeat,
// consuming the outbox, or exposing another device's state.
func (s *HTTPServer) moduleConnectionProbe(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	var req struct {
		APIKey string `json:"api_key"`
	}
	body, err := io.ReadAll(r.Body)
	if err != nil || json.Unmarshal(body, &req) != nil || strings.TrimSpace(req.APIKey) == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "api_key is required")
		return
	}
	// Use the same credential rules as first-time module registration. A web-
	// generated key is valid before its device/runtime rows have been created.
	key, exists, err := s.service.lookupAPIKey(r.Context(), strings.TrimSpace(req.APIKey))
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "diagnostics_unavailable", "credential lookup failed")
		return
	}
	if !exists || key.Disabled {
		writeError(w, http.StatusUnauthorized, "invalid_api_key", "API Key is invalid or disabled")
		return
	}
	statuses, err := s.loadModuleStatuses(r)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "diagnostics_unavailable", "module status lookup failed")
		return
	}
	result := map[string]any{"ok": true, "api_key_valid": true, "account_session_supported": true, "module_protocol": 2, "device": apiKeyDeviceName(key), "registered": false, "runtime_status": "unregistered"}
	for _, status := range statuses {
		if status.Device != apiKeyDeviceName(key) {
			continue
		}
		result["registered"] = status.Registered
		result["runtime_status"] = status.RuntimeStatus
		result["last_register_at"] = status.LastRegisterAt
		result["last_poll_at"] = status.LastPollAt
		break
	}
	writeJSON(w, http.StatusOK, result)
}

type diagnosticResponseWriter struct {
	http.ResponseWriter
	status int
}

func (w *diagnosticResponseWriter) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *diagnosticResponseWriter) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(body)
}

// Only wraps finite JSON handlers; never logs credentials, bodies or query strings.
func logModuleRequest(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &diagnosticResponseWriter{ResponseWriter: w}
		defer func() {
			status := recorder.status
			if status == 0 {
				status = http.StatusOK
			}
			log.Printf("module_request method=%s path=%s status=%d duration_ms=%d", r.Method, r.URL.Path, status, time.Since(started).Milliseconds())
		}()
		next(recorder, r)
	}
}
