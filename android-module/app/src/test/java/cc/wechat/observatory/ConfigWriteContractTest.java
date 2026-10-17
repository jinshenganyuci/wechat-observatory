package cc.wechat.observatory;

import org.junit.Test;

import java.net.MalformedURLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.*;

public class ConfigWriteContractTest {
    private Map<String, Object> settings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bridge_url", " https://gateway.example.test/ ");
        values.put("api_key", " test-key ");
        return values;
    }

    @Test public void nonceMustBeCanonicalUuid() {
        assertTrue(ConfigWriteContract.validNonce(UUID.randomUUID().toString()));
        assertFalse(ConfigWriteContract.validNonce(null));
        assertFalse(ConfigWriteContract.validNonce("1-2-3-4-5"));
        assertFalse(ConfigWriteContract.validNonce("00112233-4455-6677-8899-AABBCCDDEEFF"));
    }

    @Test public void normalizedSaveDefaultsToRealtimeAndPreservesAdvancedSettings() throws Exception {
        Map<String, Object> source = settings();
        source.put("poll_limit", "42");
        Map<String, String> result = ConfigWriteContract.normalize(source);
        assertEquals("https://gateway.example.test", result.get("bridge_url"));
        assertEquals("test-key", result.get("api_key"));
        assertEquals("1", result.get("enabled"));
        assertEquals("1", result.get("outbox_websocket_enabled"));
        assertEquals("0", result.get("background_realtime_enabled"));
        assertEquals("42", result.get("poll_limit"));
    }

    @Test public void explicitRealtimeDisableSurvivesSave() throws Exception {
        Map<String, Object> source = settings();
        source.put("enabled", "0");
        source.put("outbox_websocket_enabled", "0");
        Map<String, String> result = ConfigWriteContract.normalize(source);
        assertEquals("0", result.get("enabled"));
        assertEquals("0", result.get("outbox_websocket_enabled"));
    }

    @Test public void protectionPreferenceSurvivesEditorAndDisabledGatewaySave() throws Exception {
        Map<String, Object> source = settings();
        source.put("enabled", "0");
        source.put("outbox_websocket_enabled", "0");
        source.put("background_realtime_enabled", "1");
        Properties saved = new Properties();
        saved.putAll(ConfigWriteContract.normalize(source));
        Map<String, String> edited = ConfigWriteContract.editorSnapshot(saved);
        Map<String, String> result = ConfigWriteContract.normalize(edited);
        // The explicit preference survives; registration still requires the
        // gateway and WebSocket gates, checked by HookEntry.
        assertEquals("1", result.get("background_realtime_enabled"));
        assertEquals("0", result.get("enabled"));
        assertEquals("0", result.get("outbox_websocket_enabled"));
        edited.put("background_realtime_enabled", "0");
        assertEquals("0", ConfigWriteContract.normalize(edited).get("background_realtime_enabled"));
        assertTrue(ConfigWriteContract.editorSnapshot(new Properties()).isEmpty());
    }

    @Test public void protectionSwitchOnlyAcceptsZeroOrOne() {
        for (String value : new String[]{"true", "yes", "on", "false", "2", "-1"}) {
            Map<String, Object> source = settings();
            source.put("background_realtime_enabled", value);
            assertThrows(IllegalArgumentException.class, () -> ConfigWriteContract.normalize(source));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void callerCannotWriteSessionAllocationMetadata() throws Exception {
        Map<String, Object> source = settings();
        source.put("__generation_limit", "99999");
        ConfigWriteContract.normalize(source);
    }

    @Test(expected = IllegalArgumentException.class)
    public void callerCannotSendNonStringValues() throws Exception {
        Map<String, Object> source = settings();
        source.put("enabled", Boolean.TRUE);
        ConfigWriteContract.normalize(source);
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedInputIsRejectedBeforeAnRpcIsSent() throws Exception {
        Map<String, Object> source = settings();
        source.put("api_key", new String(new char[4097]).replace('\0', 'x'));
        ConfigWriteContract.normalize(source);
    }

    @Test(expected = MalformedURLException.class)
    public void urlCannotContainCredentials() throws Exception {
        Map<String, Object> source = settings();
        source.put("bridge_url", "https://user:secret@gateway.example.test");
        ConfigWriteContract.normalize(source);
    }

    @Test public void editorSnapshotDoesNotExposeOrOverwriteRuntimeSession() {
        Properties input = new Properties();
        input.setProperty("bridge_url", "https://gateway.example.test");
        input.setProperty("__generation_start", "100");
        input.setProperty("self_wxid", "not-configurable");
        Map<String, String> snapshot = ConfigWriteContract.editorSnapshot(input);
        assertEquals(1, snapshot.size());
        assertFalse(snapshot.containsKey("self_wxid"));
        assertFalse(snapshot.containsKey("__generation_start"));
    }

    @Test public void loadReturnsAllocatedRangeButRoutineSaveCannotReplaceIt() {
        Properties input = new Properties();
        input.setProperty("enabled", "1");
        input.setProperty("__installation_id", UUID.randomUUID().toString());
        input.setProperty("__generation_start", "100");
        input.setProperty("__generation_limit", "200");
        input.setProperty("__bridge_write_revision", "revision");
        input.setProperty("private_high_water", "900");
        Map<String, String> loaded = ConfigWriteContract.resultSnapshot(input, true);
        assertEquals("100", loaded.get("__generation_start"));
        assertEquals("200", loaded.get("__generation_limit"));
        assertFalse(loaded.containsKey("private_high_water"));
        Map<String, String> saved = ConfigWriteContract.resultSnapshot(input, false);
        assertEquals(2, saved.size());
        assertEquals("revision", saved.get("__bridge_write_revision"));
        assertFalse(saved.containsKey("__generation_start"));
        assertFalse(saved.containsKey("__installation_id"));
    }
}
