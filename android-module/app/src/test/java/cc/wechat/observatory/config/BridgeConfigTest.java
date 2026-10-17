package cc.wechat.observatory.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Properties;

import org.junit.Test;

import cc.wechat.observatory.gateway.GatewayEndpoint;

public final class BridgeConfigTest {
    @Test
    public void configuredBridgeUrlOverridesDefaultEndpoint() {
        Properties properties = new Properties();
        properties.setProperty("bridge_url", "http://192.168.1.10:8088");
        properties.setProperty("api_key", "test-key");

        BridgeConfig config = BridgeConfig.fromProperties(properties);

        assertEquals("http://192.168.1.10:8088", config.baseUrl);
        assertEquals("test-key", config.apiKey);
    }

    @Test
    public void missingBridgeUrlLeavesConnectionUnconfigured() {
        BridgeConfig config = BridgeConfig.fromProperties(new Properties());

        assertEquals("", config.baseUrl);
        assertEquals(true, config.outboxWebSocketEnabled);
    }

    @Test
    public void outboxWebSocketCanBeEnabledExplicitly() {
        Properties properties = new Properties();
        properties.setProperty("outbox_websocket_enabled", "1");

        BridgeConfig config = BridgeConfig.fromProperties(properties);

        assertEquals(true, config.outboxWebSocketEnabled);
    }

    @Test
    public void explicitHttpOnlySettingRemainsRespected() {
        Properties properties = new Properties();
        properties.setProperty("outbox_websocket_enabled", "0");

        assertEquals(false, BridgeConfig.fromProperties(properties).outboxWebSocketEnabled);
    }

    @Test
    public void backgroundRealtimeProtectionRequiresExplicitOptInAndChangesSignature() {
        Properties properties = new Properties();
        properties.setProperty("api_key", "test-key");
        BridgeConfig previousVersion = BridgeConfig.fromProperties(properties);
        assertEquals(false, previousVersion.backgroundRealtimeEnabled);

        properties.setProperty("background_realtime_enabled", "1");
        BridgeConfig optedIn = BridgeConfig.fromProperties(properties);
        assertEquals(true, optedIn.backgroundRealtimeEnabled);
        assertNotEquals(previousVersion.signature, optedIn.signature);

        properties.setProperty("background_realtime_enabled", "0");
        BridgeConfig optedOut = BridgeConfig.fromProperties(properties);
        assertEquals(false, optedOut.backgroundRealtimeEnabled);
        assertNotEquals(optedIn.signature, optedOut.signature);
    }

    @Test
    public void malformedProtectionValueCannotEnableSystemProtection() {
        Properties properties = new Properties();
        for (String value : new String[]{"true", "yes", "on", "2", "-1"}) {
            properties.setProperty("background_realtime_enabled", value);
            assertEquals(false, BridgeConfig.fromProperties(properties).backgroundRealtimeEnabled);
        }
    }

    @Test
    public void invalidBridgeUrlCannotRedirectCredentialsToFallbackServer() {
        Properties properties = new Properties();
        properties.setProperty("bridge_url", "not-a-url");

        BridgeConfig config = BridgeConfig.fromProperties(properties);

        assertEquals("", config.baseUrl);
    }

    @Test
    public void bridgeUrlChangeUpdatesConfigSignature() {
        Properties first = new Properties();
        first.setProperty("bridge_url", "https://47.108.171.42/observatory");
        Properties second = new Properties();
        second.setProperty("bridge_url", "https://47.108.232.203/observatory");

        assertNotEquals(
                BridgeConfig.fromProperties(first).signature,
                BridgeConfig.fromProperties(second).signature);
    }

    @Test
    public void sharingRevisionDoesNotChangeBusinessConfigurationSignature() {
        Properties properties = new Properties();
        properties.setProperty("bridge_url", "https://example.test");
        properties.setProperty("api_key", "test-key");
        properties.setProperty("__bridge_write_revision", "first-save");
        BridgeConfig before = BridgeConfig.fromProperties(properties);
        properties.setProperty("__bridge_write_revision", "second-save");
        BridgeConfig after = BridgeConfig.fromProperties(properties);
        assertEquals(before.signature, after.signature);
        assertEquals(before.apiKey, after.apiKey);
    }

    @Test
    public void contactSnapshotDefaultsToMaximumAcceptedSize() {
        BridgeConfig config = BridgeConfig.fromProperties(new Properties());

        assertEquals(10000, BridgeConfig.DEFAULT_CONTACT_SYNC_LIMIT);
        assertEquals(10000, config.contactSyncLimit);
    }

    @Test
    public void staleMessageReplayDefaultsAreBoundedAndConfigurable() {
        Properties properties = new Properties();
        properties.setProperty("stale_message_grace_ms", "300000");
        properties.setProperty("stale_message_replay_limit", "25");

        BridgeConfig configured = BridgeConfig.fromProperties(properties);
        BridgeConfig defaults = BridgeConfig.fromProperties(new Properties());

        assertEquals(300000L, configured.staleMessageGraceMs);
        assertEquals(25, configured.staleMessageReplayLimit);
        assertEquals(900000L, defaults.staleMessageGraceMs);
        assertEquals(500, defaults.staleMessageReplayLimit);
    }

    @Test
    public void invalidStaleMessageReplayLimitFallsBackToTheDefault() {
        Properties properties = new Properties();
        properties.setProperty("stale_message_replay_limit", "2147483648");

        BridgeConfig config = BridgeConfig.fromProperties(properties);

        assertEquals(BridgeConfig.DEFAULT_STALE_MESSAGE_REPLAY_LIMIT, config.staleMessageReplayLimit);
    }
}
