package cc.wechat.observatory.config;

import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConfigSourcesTest {
    @Test public void liveSettingsWinWithoutReadingStaleMirror() {
        Properties current = new Properties();
        current.setProperty("api_key", "updated-key");
        Properties selected = ConfigSources.firstAvailable(() -> current, () -> {
            fail("An available provider must prevent stale mirror reads");
            return null;
        });
        assertSame(current, selected);
    }

    @Test public void explicitDisableAndEmptyKeyAreNotReplacedByMirror() {
        Properties disabled = new Properties();
        disabled.setProperty("enabled", "0");
        disabled.setProperty("api_key", "");
        Properties stale = new Properties();
        stale.setProperty("api_key", "old-key");
        assertSame(disabled, ConfigSources.firstAvailable(() -> disabled, () -> stale));
    }

    @Test public void publishedEmptySnapshotNeverRestoresOlderCredentials() {
        Properties cleared = new Properties();
        cleared.setProperty("__bridge_write_revision", "new-save");
        Properties selected = ConfigSources.firstAvailable(() -> cleared, () -> {
            fail("Published cleared settings must not read an older provider or disk mirror");
            return null;
        });
        BridgeConfig config = BridgeConfig.fromProperties(selected);
        assertEquals("", config.apiKey);
        assertEquals("", config.baseUrl);
    }

    @Test public void unavailableLiveSourceAllowsMirrorFallback() {
        Properties mirror = new Properties();
        mirror.setProperty("bridge_url", "https://example.test");
        assertSame(mirror, ConfigSources.firstAvailable(Properties::new, () -> mirror));
    }
}
