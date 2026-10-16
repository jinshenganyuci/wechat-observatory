package cc.wechat.observatory;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import cc.wechat.observatory.gateway.GatewayEndpoint;

/** Bounded string-only protocol; never includes runtime account identity. */
final class ConfigWriteContract {
    static final String MODULE_PACKAGE = "cc.wechat.observatory";
    static final String WRITE_ACTIVITY = MODULE_PACKAGE + ".ConfigWriteActivity";
    static final String ACTION_LOAD = MODULE_PACKAGE + ".config.LOAD";
    static final String ACTION_SAVE = MODULE_PACKAGE + ".config.SAVE";
    static final String EXTRA_VALUES = MODULE_PACKAGE + ".config.values";
    static final String EXTRA_NONCE = MODULE_PACKAGE + ".config.nonce";
    static final String EXTRA_STATUS = MODULE_PACKAGE + ".config.status";
    static final String EXTRA_NEEDS_SESSION = MODULE_PACKAGE + ".config.needs_session";
    static final String REVISION_KEY = "__bridge_write_revision";
    static final String OK = "ok";
    static final String DENIED = "denied";
    static final String INVALID = "invalid";
    static final String FAILED = "failed";
    static final int REQUEST_CODE = 0x4f42;
    static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "enabled", "bridge_url", "api_key", "poll_interval_ms", "poll_limit",
            "outbox_websocket_enabled", "contact_sync_interval_ms", "contact_sync_limit",
            "contact_include_chatrooms", "media_upload_enabled", "media_upload_limit_bytes",
            "stale_message_grace_ms", "stale_message_replay_limit")));

    private ConfigWriteContract() { }

    static boolean allowedCaller(String packageName) {
        return "com.tencent.mm".equals(packageName);
    }

    static boolean validNonce(String nonce) {
        if (nonce == null || nonce.length() != 36) return false;
        try {
            return UUID.fromString(nonce).toString().equals(nonce);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static Map<String, String> editorSnapshot(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = properties.getProperty(key);
            if (value != null) values.put(key, value);
        }
        return values;
    }

    static Map<String, String> normalize(Map<String, ?> input) throws MalformedURLException {
        if (input == null || input.size() > KEYS.size()) throw new IllegalArgumentException("invalid config size");
        Map<String, String> result = new LinkedHashMap<>();
        int size = 0;
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            if (!KEYS.contains(entry.getKey()) || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("invalid config key or type");
            }
            String value = ((String) entry.getValue()).trim();
            size += value.length();
            if (value.length() > 4096 || size > 16384) throw new IllegalArgumentException("config too large");
            if (!value.isEmpty()) result.put(entry.getKey(), value);
        }
        String enabled = result.containsKey("enabled") ? result.get("enabled") : "1";
        String websocket = result.containsKey("outbox_websocket_enabled")
                ? result.get("outbox_websocket_enabled") : "1";
        if (!("0".equals(enabled) || "1".equals(enabled))
                || !("0".equals(websocket) || "1".equals(websocket))) {
            throw new IllegalArgumentException("invalid switch value");
        }
        result.put("enabled", enabled);
        result.put("outbox_websocket_enabled", websocket);
        result.put("bridge_url", GatewayEndpoint.normalizeBaseUrl(result.get("bridge_url")));
        return result;
    }

    static boolean isOn(String value, boolean defaultValue) {
        if (value == null) return defaultValue;
        return !("0".equals(value) || "false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value));
    }

    static final Set<String> RUNTIME_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "__installation_id", "__generation_start", "__generation_limit", REVISION_KEY)));

    static Map<String, String> resultSnapshot(Properties properties, boolean loading) {
        Map<String, String> result = editorSnapshot(properties);
        for (String key : RUNTIME_KEYS) {
            if (!loading && !REVISION_KEY.equals(key)) continue;
            String value = properties.getProperty(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }
}
