package cc.wechat.observatory.config;

import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeRuntimeConfigTest {
    private static final String INSTANCE = "5ab46c33-0f96-4c49-9be7-549953a709f9";

    @Before public void startWithoutRuntime() { BridgeConfig.clearRuntimeSettings(); }
    @After public void discardRuntime() { BridgeConfig.clearRuntimeSettings(); }

    @Test public void loadAndEditorReturnIndependentSnapshots() {
        Properties received = initialSnapshot();
        BridgeConfig.acceptRuntimeSettings(received);
        received.clear();
        Properties editor = BridgeConfig.readSettingsForEditor(null);
        assertEquals("test-key", editor.getProperty("api_key"));
        editor.setProperty("api_key", "unsaved-edit");
        editor.remove("__generation_start");
        assertEquals("test-key", BridgeConfig.readSettingsForEditor(null).getProperty("api_key"));
        assertTrue(BridgeConfig.isRuntimeLoaded());
        assertTrue(BridgeConfig.hasRuntimeSession());
    }

    @Test public void saveReplacesBusinessValuesWhileRetainingReservedRuntimeRange() {
        BridgeConfig.acceptRuntimeSettings(initialSnapshot());
        Properties saved = new Properties();
        saved.setProperty("bridge_url", "https://replacement.example.test");
        saved.setProperty("api_key", "replacement-test-key");
        saved.setProperty("__bridge_write_revision", "second-save");
        BridgeConfig.acceptRuntimeSettings(saved);
        Properties current = BridgeConfig.readSettingsForEditor(null);
        assertEquals("replacement-test-key", current.getProperty("api_key"));
        assertEquals("second-save", current.getProperty("__bridge_write_revision"));
        assertEquals(INSTANCE, current.getProperty("__installation_id"));
        assertEquals("1000001", current.getProperty("__generation_start"));
        assertEquals("2000000", current.getProperty("__generation_limit"));
        assertNull(current.getProperty("media_upload_enabled"));
        assertTrue(BridgeConfig.hasRuntimeSession());
    }

    @Test public void duplicateLoadDoesNotResetAnAlreadyAllocatedRange() {
        BridgeConfig.acceptRuntimeSettings(initialSnapshot());
        Properties duplicateLoad = initialSnapshot();
        duplicateLoad.setProperty("__generation_start", "2000001");
        duplicateLoad.setProperty("__generation_limit", "3000000");
        BridgeConfig.acceptRuntimeSettings(duplicateLoad);
        Properties current = BridgeConfig.readSettingsForEditor(null);
        assertEquals("1000001", current.getProperty("__generation_start"));
        assertEquals("2000000", current.getProperty("__generation_limit"));
    }

    @Test public void clearingSavedConfigurationCannotResurrectPreviousCredentials() {
        BridgeConfig.acceptRuntimeSettings(initialSnapshot());
        Properties clearedSave = new Properties();
        clearedSave.setProperty("__bridge_write_revision", "cleared-save");
        BridgeConfig.acceptRuntimeSettings(clearedSave);
        for (int i = 0; i < 2; i++) {
            Properties current = BridgeConfig.readSettingsForEditor(null);
            BridgeConfig config = BridgeConfig.fromProperties(current);
            assertEquals("", config.apiKey);
            assertEquals("", config.baseUrl);
            assertNull(current.getProperty("api_key"));
            assertEquals("cleared-save", current.getProperty("__bridge_write_revision"));
            assertTrue(BridgeConfig.hasRuntimeSession());
        }
    }

    @Test public void removalDiscardsRuntimeIncludingIdentityAndAllowsFreshInstallation() {
        BridgeConfig.acceptRuntimeSettings(initialSnapshot());
        BridgeConfig.clearRuntimeSettings();
        assertFalse(BridgeConfig.isRuntimeLoaded());
        assertFalse(BridgeConfig.hasRuntimeSession());
        assertTrue(BridgeConfig.readSettingsForEditor(null).isEmpty());
        BridgeConfig empty = BridgeConfig.fromProperties(BridgeConfig.readSettingsForEditor(null));
        assertEquals("", empty.apiKey);
        assertEquals("", empty.baseUrl);

        Properties freshInstallation = initialSnapshot();
        freshInstallation.setProperty("__installation_id", "0a5a192f-e850-49bc-9a51-17bb021a10be");
        freshInstallation.setProperty("__generation_start", "1");
        freshInstallation.setProperty("__generation_limit", "1000000");
        BridgeConfig.acceptRuntimeSettings(freshInstallation);
        assertEquals("0a5a192f-e850-49bc-9a51-17bb021a10be",
                BridgeConfig.readSettingsForEditor(null).getProperty("__installation_id"));
        assertEquals("1", BridgeConfig.readSettingsForEditor(null).getProperty("__generation_start"));
    }

    @Test public void businessSettingsAloneDoNotInventAccountSession() {
        Properties settings = new Properties();
        settings.setProperty("api_key", "test-key");
        BridgeConfig.acceptRuntimeSettings(settings);
        assertTrue(BridgeConfig.isRuntimeLoaded());
        assertFalse(BridgeConfig.hasRuntimeSession());
    }

    @Test public void reservationMetadataDoesNotChangeBusinessSignature() {
        Properties first = initialSnapshot();
        Properties second = initialSnapshot();
        second.setProperty("__generation_start", "2000001");
        second.setProperty("__generation_limit", "3000000");
        second.setProperty("__bridge_write_revision", "new-revision");
        assertEquals(BridgeConfig.fromProperties(first).signature,
                BridgeConfig.fromProperties(second).signature);
    }

    private static Properties initialSnapshot() {
        Properties values = new Properties();
        values.setProperty("bridge_url", "https://gateway.example.test");
        values.setProperty("api_key", "test-key");
        values.setProperty("media_upload_enabled", "1");
        values.setProperty("__installation_id", INSTANCE);
        values.setProperty("__generation_start", "1000001");
        values.setProperty("__generation_limit", "2000000");
        values.setProperty("__bridge_write_revision", "initial-save");
        return values;
    }
}
