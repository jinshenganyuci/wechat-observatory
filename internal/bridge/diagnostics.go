package bridge

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

// moduleDiagnostics authenticates without registering, refreshing a heartbeat,
// consuming the outbox, or exposing another device's state.
func (s *HTTPServer) moduleDiagnostics(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	var req struct {
		APIKey string `json:"api_key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.APIKey) == "" {
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
	result := map[string]any{"ok": true, "api_key_valid": true, "device": apiKeyDeviceName(key), "registered": false, "runtime_status": "unregistered"}
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
