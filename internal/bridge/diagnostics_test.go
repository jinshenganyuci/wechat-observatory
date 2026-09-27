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

func TestModuleDiagnosticsReadOnlyAndScoped(t *testing.T) {
	service := newTestService("")
	device := service.cfg.Devices["phone-a"]
	device.WxID = ""
	service.cfg.Devices["phone-a"] = device
	server := NewHTTPServer(service, "admin")
	request := func(key string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		body, _ := json.Marshal(map[string]string{"api_key": key})
		server.Handler().ServeHTTP(recorder, httptest.NewRequest("POST", "/module/diagnostics", bytes.NewReader(body)))
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

func TestModuleDiagnosticsRegisteredState(t *testing.T) {
	service := newTestService("")
	if _, err := service.RegisterModule(t.Context(), ModuleRegistrationRequest{APIKey: testAPIKey, WxID: "wxid_self"}); err != nil {
		t.Fatal(err)
	}
	server := NewHTTPServer(service, "admin")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, httptest.NewRequest("POST", "/module/diagnostics", strings.NewReader(`{"api_key":"wechat-a-key"}`)))
	var data map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &data); err != nil {
		t.Fatal(err)
	}
	if response.Code != 200 || data["registered"] != true {
		t.Fatalf("unexpected state: %s", response.Body)
	}
}

func TestModuleDiagnosticsRejectsMissingAndOversizedCredentials(t *testing.T) {
	server := NewHTTPServer(newTestService(""), "admin").Handler()
	for _, body := range []string{`{}`, `{"api_key":""}`, `{"api_key":"` + strings.Repeat("x", 5000) + `"}`} {
		response := httptest.NewRecorder()
		server.ServeHTTP(response, httptest.NewRequest("POST", "/module/diagnostics", strings.NewReader(body)))
		if response.Code != 400 {
			t.Fatalf("got %d", response.Code)
		}
	}
}

func TestModuleRequestLogDoesNotExposeCredentials(t *testing.T) {
	var logs bytes.Buffer
	original := log.Writer()
	log.SetOutput(&logs)
	defer log.SetOutput(original)
	server := NewHTTPServer(newTestService(""), "admin").Handler()
	response := httptest.NewRecorder()
	server.ServeHTTP(response, httptest.NewRequest("POST", "/module/diagnostics?api_key=query-secret", strings.NewReader(`{"api_key":"body-secret"}`)))
	output := logs.String()
	if !strings.Contains(output, "path=/module/diagnostics status=401") {
		t.Fatalf("missing status: %s", output)
	}
	if strings.Contains(output, "secret") {
		t.Fatalf("secret in log: %s", output)
	}
}

func TestModuleDiagnosticsAcceptsFreshWebGeneratedKeyBeforeRegistration(t *testing.T) {
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
	server.ServeHTTP(response, httptest.NewRequest("POST", "/module/diagnostics", bytes.NewReader(body)))
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
