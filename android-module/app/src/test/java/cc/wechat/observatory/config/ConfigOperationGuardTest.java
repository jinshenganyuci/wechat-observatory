package cc.wechat.observatory.config;

import cc.wechat.observatory.wechat.QueueSubmissionRetrier;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigOperationGuardTest {
    @Before public void initialize() { BridgeConfig.clearRuntimeSettings(); }
    @After public void clear() { BridgeConfig.clearRuntimeSettings(); }

    @Test public void currentConfigurationAllowsInitialRegistrationWithoutBoundAccount() {
        BridgeConfig config = publish(configured());
        assertNull(config.runtimeAccount);
        assertTrue(ConfigOperationGuard.allows(config, latest()));
    }

    @Test public void disablingWhileQueueIsBusyPreventsTheNextNativeSubmission() throws Exception {
        BridgeConfig captured = publish(configured());
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> QueueSubmissionRetrier.awaitAccepted(
                () -> {
                    ConfigOperationGuard.requireCurrent(captured, latest());
                    submitted.incrementAndGet();
                    return false;
                }, delay -> {
                    sleeps.incrementAndGet();
                    Properties disabled = configured();
                    disabled.setProperty("enabled", "0");
                    publish(disabled);
                }, 5, 500));
        assertEquals(1, submitted.get());
        assertEquals(1, sleeps.get());
    }

    @Test public void clearBeforeAQueuedMainThreadCallbackPreventsItsFirstSubmission() {
        BridgeConfig captured = publish(configured());
        Properties cleared = new Properties();
        cleared.setProperty("__bridge_write_revision", "cleared");
        publish(cleared);
        assertThrows(IllegalStateException.class, () -> ConfigOperationGuard.requireCurrent(captured, latest()));
    }

    @Test public void clearingRuntimePreventsAnHttpRetryAfterTheFirstAttemptStarted() {
        BridgeConfig captured = publish(configured());
        ConfigOperationGuard.requireCurrent(captured, latest()); // First request admitted.
        BridgeConfig.clearRuntimeSettings(); // Removed while that request was in flight.
        assertThrows(IllegalStateException.class, () -> ConfigOperationGuard.requireCurrent(captured, latest()));
    }

    @Test public void changedServerOrCredentialsRejectOldWorkersButAllowFreshRegistration() {
        BridgeConfig old = publish(configured());
        Properties changed = configured();
        changed.setProperty("bridge_url", "https://replacement.example.test");
        BridgeConfig updated = publish(changed);
        assertFalse(ConfigOperationGuard.allows(old, latest()));
        assertTrue(ConfigOperationGuard.allows(updated, latest()));

        changed.setProperty("api_key", "new-test-key");
        BridgeConfig changedCredentials = publish(changed);
        assertFalse(ConfigOperationGuard.allows(updated, latest()));
        assertTrue(ConfigOperationGuard.allows(changedCredentials, latest()));
    }

    @Test public void savingIdenticalBusinessValuesDoesNotCancelAnAcceptedOperation() {
        BridgeConfig captured = publish(configured());
        Properties saved = configured();
        saved.setProperty("__bridge_write_revision", "another-save");
        publish(saved);
        assertTrue(ConfigOperationGuard.allows(captured, latest()));
    }

    @Test public void matchingDisabledOrIncompleteConfigurationsStillCannotStartRequests() {
        Properties disabled = configured();
        disabled.setProperty("enabled", "0");
        BridgeConfig disabledConfig = publish(disabled);
        assertFalse(ConfigOperationGuard.allows(disabledConfig, latest()));
        disabled.remove("enabled");
        disabled.remove("api_key");
        BridgeConfig noKey = publish(disabled);
        assertFalse(ConfigOperationGuard.allows(noKey, latest()));
        assertFalse(ConfigOperationGuard.allows(null, latest()));
    }

    private static BridgeConfig publish(Properties values) {
        BridgeConfig.acceptRuntimeSettings(values);
        return latest();
    }

    private static BridgeConfig latest() {
        return BridgeConfig.fromProperties(BridgeConfig.readSettingsForEditor(null));
    }

    private static Properties configured() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "1");
        properties.setProperty("bridge_url", "https://gateway.example.test");
        properties.setProperty("api_key", "test-key");
        return properties;
    }
}
