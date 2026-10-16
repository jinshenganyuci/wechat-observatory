package cc.wechat.observatory.config;

import android.content.Context;

import java.util.Locale;
import java.util.Properties;

import cc.wechat.observatory.gateway.GatewayEndpoint;
import cc.wechat.observatory.util.BridgeLogger;
import cc.wechat.observatory.util.Strings;
import cc.wechat.observatory.wechat.RuntimeAccount;

public final class BridgeConfig {
    public static final int DEFAULT_CONTACT_SYNC_LIMIT = 10000;
    public static final long DEFAULT_STALE_MESSAGE_GRACE_MS = 15L * 60L * 1000L;
    public static final int DEFAULT_STALE_MESSAGE_REPLAY_LIMIT = 500;

    private static volatile long lastConfigLogAt;
    private static Properties runtimeSettings = new Properties();
    private static boolean runtimeLoaded;

    /** Ephemeral snapshot supplied by the module-owned Binder service. */
    public static synchronized void acceptRuntimeSettings(Properties values) {
        Properties next = new Properties();
        if (values != null) next.putAll(values);
        for (String key : new String[]{"__installation_id", "__generation_start", "__generation_limit"}) {
            String existing = runtimeSettings.getProperty(key);
            if (existing != null) next.setProperty(key, existing);
        }
        runtimeSettings = next;
        runtimeLoaded = true;
    }

    public static synchronized boolean isRuntimeLoaded() { return runtimeLoaded; }

    public static synchronized boolean hasRuntimeSession() {
        return runtimeSettings.containsKey("__installation_id")
                && runtimeSettings.containsKey("__generation_start")
                && runtimeSettings.containsKey("__generation_limit");
    }

    public static synchronized void clearRuntimeSettings() {
        runtimeSettings.clear();
        runtimeLoaded = false;
    }

    public static synchronized Properties readSettingsForEditor(Context ignored) {
        Properties copy = new Properties();
        copy.putAll(runtimeSettings);
        return copy;
    }

    public boolean enabled;
    public String baseUrl;
    public String device;
    public String selfWxid;
    public String apiKey;
    public String nickname;
    /** Filled only from the current WeChat kernel, never from saved operator settings. */
    public RuntimeAccount runtimeAccount;
    public long pollIntervalMs;
    public int pollLimit;
    public boolean outboxWebSocketEnabled;
    public long contactSyncIntervalMs;
    public int contactSyncLimit;
    public boolean includeChatrooms;
    public boolean mediaUploadEnabled;
    public long mediaUploadLimitBytes;
    public long staleMessageGraceMs;
    public int staleMessageReplayLimit;
    public String signature;

    private BridgeConfig() {
    }

    public static BridgeConfig load(Context context) {
        Properties properties = readProperties(context);
        BridgeConfig config = fromProperties(properties);
        logConfigOnce(config, properties);
        return config;
    }

    static BridgeConfig fromProperties(Properties properties) {
        BridgeConfig config = new BridgeConfig();
        config.enabled = !"0".equals(setting(properties, "enabled", "1"));
        config.baseUrl = baseUrlSetting(properties);
        config.device = "";
        config.selfWxid = "";
        config.apiKey = setting(properties, "api_key", "");
        config.nickname = "";
        config.pollIntervalMs = longSetting(properties, "poll_interval_ms", 1000L);
        config.pollLimit = (int) longSetting(properties, "poll_limit", 20L);
        // WebSocket is the primary reply channel; explicit 0 retains HTTP-only mode.
        config.outboxWebSocketEnabled = booleanSetting(properties, "outbox_websocket_enabled", true);
        config.contactSyncIntervalMs = longSetting(properties, "contact_sync_interval_ms", 600000L);
        config.contactSyncLimit = (int) longSetting(properties, "contact_sync_limit", DEFAULT_CONTACT_SYNC_LIMIT);
        config.includeChatrooms = booleanSetting(properties, "contact_include_chatrooms", true);
        config.mediaUploadEnabled = booleanSetting(properties, "media_upload_enabled", false);
        config.mediaUploadLimitBytes = longSetting(properties, "media_upload_limit_bytes", 5L * 1024L * 1024L);
        config.staleMessageGraceMs = nonNegativeLongSetting(properties, "stale_message_grace_ms", DEFAULT_STALE_MESSAGE_GRACE_MS);
        config.staleMessageReplayLimit = nonNegativeIntSetting(properties, "stale_message_replay_limit", DEFAULT_STALE_MESSAGE_REPLAY_LIMIT);
        config.signature = configSignature(properties);
        return config;
    }

    private static Properties readProperties(Context context) {
        // No disk or IPC on the message path. The module service owns persistent data.
        return readSettingsForEditor(context);
    }

    private static void logConfigOnce(BridgeConfig config, Properties properties) {
        long now = System.currentTimeMillis();
        if (now - lastConfigLogAt < 30000L) {
            return;
        }
        lastConfigLogAt = now;
        BridgeLogger.log("config loaded keys=" + businessKeyCount(properties)
                + " enabled=" + config.enabled
                + " baseUrl=" + (Strings.isBlank(config.baseUrl) ? "<empty>" : "<set>")
                + " device=" + config.device
                + " selfWxid=" + (Strings.isBlank(config.selfWxid) ? "<empty>" : config.selfWxid)
                + " apiKey=" + (Strings.isBlank(config.apiKey) ? "<empty>" : "<set>")
                + " pollIntervalMs=" + config.pollIntervalMs
                + " outboxWebSocketEnabled=" + config.outboxWebSocketEnabled
                + " includeChatrooms=" + config.includeChatrooms);
    }

    private static int businessKeyCount(Properties properties) {
        int count = 0;
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("__")) count++;
        }
        return count;
    }

    private static String setting(Properties properties, String name, String fallback) {
        try {
            String value = properties.getProperty(name);
            return Strings.isBlank(value) ? fallback : value.trim();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String baseUrlSetting(Properties properties) {
        String value = setting(properties, "bridge_url", GatewayEndpoint.PRODUCTION_BASE_URL);
        try {
            return GatewayEndpoint.normalizeBaseUrl(value);
        } catch (Throwable ignored) {
            return GatewayEndpoint.PRODUCTION_BASE_URL;
        }
    }

    private static long longSetting(Properties properties, String name, long fallback) {
        try {
            String value = properties.getProperty(name);
            return Strings.isBlank(value) ? fallback : Long.parseLong(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long nonNegativeLongSetting(Properties properties, String name, long fallback) {
        long value = longSetting(properties, name, fallback);
        return value >= 0L ? value : fallback;
    }

    private static int nonNegativeIntSetting(Properties properties, String name, int fallback) {
        long value = nonNegativeLongSetting(properties, name, fallback);
        return value <= Integer.MAX_VALUE ? (int) value : fallback;
    }

    private static boolean booleanSetting(Properties properties, String name, boolean fallback) {
        try {
            String value = properties.getProperty(name);
            if (Strings.isBlank(value)) {
                return fallback;
            }
            value = value.trim().toLowerCase(Locale.US);
            return "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String configSignature(Properties properties) {
        StringBuilder out = new StringBuilder();
        for (String key : new String[]{
                "enabled",
                "bridge_url",
                "api_key",
                "poll_interval_ms",
                "poll_limit",
                "outbox_websocket_enabled",
                "contact_sync_interval_ms",
                "contact_sync_limit",
                "contact_include_chatrooms",
                "media_upload_enabled",
                "media_upload_limit_bytes",
                "stale_message_grace_ms",
                "stale_message_replay_limit"
        }) {
            out.append(key).append('=').append(setting(properties, key, "")).append('\n');
        }
        return out.toString();
    }
}
