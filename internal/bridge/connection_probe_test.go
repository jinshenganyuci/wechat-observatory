package bridge

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestModuleConnectionProbeReadOnlyAndScoped(t *testing.T) {
	service := newTestService("")
	device := service.cfg.Devices["phone-a"]
	device.WxID = ""
	service.cfg.Devices["phone-a"] = device
	server := NewHTTPServer(service, "admin")
	request := func(key string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		body, _ := json.Marshal(map[string]string{"api_key": key})
		server.Handler().ServeHTTP(recorder, httptest.NewRequest("POST", "/module/connection", bytes.NewReader(body)))
		return recorder
	}
	before, _ := json.Marshal(server.moduleStatusViews())
	for i := 0; i < 2; i++ {
		response := request(testAPIKey)
		if response.Code != http.StatusOK {
			t.Fatalf("status=%d body=%s", response.Code, response.Body)
		}
		if response.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("diagnostics must not be cached")
		}
		var data map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
			t.Fatal(err)
		}
		if data["api_key_valid"] != true || data["device"] != "phone-a" || data["registered"] != false {
			t.Fatalf("unexpected: %v", data)
		}
		if data["account_session_supported"] != true || data["module_protocol"] != float64(2) {
			t.Fatalf("missing account-session protocol support: %v", data)
		}
		for _, forbidden := range []string{testAPIKey, "phone-b", "wxid_self", "credential_ref"} {
			if strings.Contains(response.Body.String(), forbidden) {
				t.Fatalf("leaked %q", forbidden)
			}
		}
	}
	after, _ := json.Marshal(server.moduleStatusViews())
	if !bytes.Equal(before, after) {
		t.Fatalf("diagnostics mutated runtime: %s -> %s", before, after)
	}
	if request("wrong").Code != http.StatusUnauthorized {
		t.Fatal("invalid key accepted")
	}
	if _, err := service.SetAPIKeyEnabled(t.Context(), testAPIKey, false); err != nil {
		t.Fatal(err)
	}
	if request(testAPIKey).Code != http.StatusUnauthorized {
		t.Fatal("disabled key accepted")
	}
}

func TestModuleConnectionProbeRegisteredState(t *testing.T) {
	service := newTestService("")
	if _, err := service.RegisterModule(t.Context(), ModuleRegistrationRequest{APIKey: testAPIKey, WxID: "wxid_self"}); err != nil {
		t.Fatal(err)
	}
	server := NewHTTPServer(service, "admin")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, httptest.NewRequest("POST", "/module/connection", strings.NewReader(`{"api_key":"wechat-a-key"}`)))
	var data map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
		t.Fatal(err)
	}
	if response.Code != 200 || data["registered"] != true {
		t.Fatalf("unexpected state: %s", response.Body)
	}
}

func TestModuleConnectionProbeRejectsMissingAndOversizedCredentials(t *testing.T) {
	server := NewHTTPServer(newTestService(""), "admin").Handler()
	for _, body := range []string{`{}`, `{"api_key":""}`, `{"api_key":"` + strings.Repeat("x", 5000) + `"}`,
		`{"api_key":"wechat-a-key"}` + strings.Repeat(" ", 5000),
		`{"api_key":"wechat-a-key"}{"api_key":"another-key"}`} {
		response := httptest.NewRecorder()
		server.ServeHTTP(response, httptest.NewRequest("POST", "/module/connection", strings.NewReader(body)))
		if response.Code != 400 {
			t.Fatalf("got %d", response.Code)
		}
	}
}

func TestLegacyDiagnosticProbeRemainsReadOnlyAndAuthenticated(t *testing.T) {
	service, records := newDiagnosticService()
	server := NewHTTPServer(service, "admin")
	handler := server.Handler()
	before, _ := json.Marshal(server.moduleStatusViews())
	for _, key := range []string{"m-key-live", "typo-key", "m-key-off"} {
		body, _ := json.Marshal(map[string]string{"api_key": key})
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/module/diagnostics", bytes.NewReader(body)))
		if key == "m-key-live" {
			var data map[string]any
			if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
				t.Fatal(err)
			}
			if response.Code != http.StatusOK || data["api_key_valid"] != true ||
				data["account_session_supported"] != true || data["registered"] != false {
				t.Fatalf("legacy probe response: %d %s", response.Code, response.Body)
			}
		} else if response.Code != http.StatusUnauthorized {
			t.Fatalf("legacy probe accepted invalid credential: %d", response.Code)
		}
	}
	after, _ := json.Marshal(server.moduleStatusViews())
	if !bytes.Equal(before, after) || len(records.snapshot()) != 0 {
		t.Fatal("connection probe changed registration state or stored a diagnostic")
	}
}

func TestDiagnosticReportsStillStoreWithStageInsteadOfProbingCredentials(t *testing.T) {
	service, records := newDiagnosticService()
	handler := NewHTTPServer(service, "admin").Handler()
	for _, stage := range []string{"identity", ""} {
		body, _ := json.Marshal(ModuleDiagnosticReport{
			APIKey: "typo-key", Stage: stage, Message: "current account unavailable",
		})
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/module/diagnostics", bytes.NewReader(body)))
		if response.Code != http.StatusAccepted {
			t.Fatalf("report must be accepted even with an invalid key: %d %s", response.Code, response.Body)
		}
		if strings.Contains(response.Body.String(), "api_key_valid") {
			t.Fatal("diagnostic upload exposed authentication status")
		}
	}
	stored := records.snapshot()
	if len(stored) != 2 || stored[0].Stage != "identity" || stored[1].Stage != "unspecified" ||
		stored[0].KeyStatus != DiagnosticKeyUnknown {
		t.Fatalf("diagnostic upload routing changed: %+v", stored)
	}
}

func TestModuleRequestLogDoesNotExposeCredentials(t *testing.T) {
	var logs bytes.Buffer
	original := log.Writer()
	log.SetOutput(&logs)
	defer log.SetOutput(original)
	server := NewHTTPServer(newTestService(""), "admin").Handler()
	response := httptest.NewRecorder()
	server.ServeHTTP(response, httptest.NewRequest("POST", "/module/connection?api_key=query-secret", strings.NewReader(`{"api_key":"body-secret"}`)))
	output := logs.String()
	if !strings.Contains(output, "path=/module/connection status=401") {
		t.Fatalf("missing status: %s", output)
	}
	if strings.Contains(output, "secret") {
		t.Fatalf("secret in log: %s", output)
	}
}

func TestModuleConnectionProbeAcceptsFreshWebGeneratedKeyBeforeRegistration(t *testing.T) {
	service := newTestService("")
	key, err := service.UpsertAPIKey(t.Context(), APIKeyUpsertRequest{Nickname: "Fresh phone"})
	if err != nil {
		t.Fatal(err)
	}
	if _, exists := service.Device(key.Device); exists {
		t.Fatal("fixture already has a device")
	}
	response := httptest.NewRecorder()
	body, _ := json.Marshal(map[string]string{"api_key": key.APIKey})
	server := NewHTTPServer(service, "admin").Handler()
	server.ServeHTTP(response, httptest.NewRequest("POST", "/module/connection", bytes.NewReader(body)))
	if response.Code != 200 {
		t.Fatalf("valid new API Key rejected before first registration: status=%d body=%s", response.Code, response.Body)
	}
	var data map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
		t.Fatal(err)
	}
	if data["api_key_valid"] != true || data["registered"] != false || data["runtime_status"] != "unregistered" {
		t.Fatalf("incorrect diagnostic: %v", data)
	}
	if _, exists := service.Device(key.Device); exists {
		t.Fatal("diagnostics must not register device")
	}
}
