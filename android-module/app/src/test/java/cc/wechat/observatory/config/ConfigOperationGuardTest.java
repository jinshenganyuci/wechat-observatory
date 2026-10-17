package cc.wechat.observatory.config;

import cc.wechat.observatory.wechat.QueueSubmissionRetrier;
import cc.wechat.observatory.wechat.AccountSession;
import cc.wechat.observatory.wechat.RuntimeAccount;
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

    @Test public void deletingThenSavingSameCredentialsCannotReviveOldOperations() {
        Properties original = configured();
        original.setProperty("__installation_id", "5ab46c33-0f96-4c49-9be7-549953a709f9");
        original.setProperty("__generation_start", "1");
        original.setProperty("__generation_limit", "1000000");
        BridgeConfig old = publish(original);
        publish(new Properties());
        assertFalse(ConfigOperationGuard.allows(old, latest()));
        Properties recreated = new Properties();
        recreated.putAll(original);
        recreated.setProperty("__installation_id", "0a5a192f-e850-49bc-9a51-17bb021a10be");
        BridgeConfig current = publish(recreated);
        assertFalse(ConfigOperationGuard.allows(old, latest()));
        assertTrue(ConfigOperationGuard.allows(current, latest()));
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

    @Test public void initialIdentityReadCanPublishBeforeAnyAccountIsBound() {
        BridgeConfig captured = publish(configured());
        assertTrue(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 1, null, null));
    }

    @Test public void disableThenReenableCannotReviveAnEarlierIdentityRead() {
        BridgeConfig captured = publish(configured());
        RuntimeAccount account = account("first");
        Properties disabled = configured();
        disabled.setProperty("enabled", "0");
        publish(disabled);
        publish(configured()); // Same business signature, different accepted configuration revision.
        assertTrue(ConfigOperationGuard.allows(captured, latest()));
        assertFalse(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 3, account, account));
        assertTrue(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 3, 3, account, account));
    }

    @Test public void staleIdentitySuccessOrFailureCannotOverwriteAReplacementBinding() {
        BridgeConfig captured = publish(configured());
        RuntimeAccount old = account("first");
        RuntimeAccount replacement = account("first"); // Even the same login is a new binding.
        assertTrue(old.sameLogin(replacement));
        assertFalse(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 1, old, replacement));
        assertFalse(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 1, null, replacement));
        assertFalse(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 1, old, null));
    }

    @Test public void clearingConfigurationRejectsIdentityResultsAndControlFrames() {
        BridgeConfig captured = publish(configured());
        RuntimeAccount account = account("first");
        captured.runtimeAccount = account;
        publish(new Properties());
        assertFalse(ConfigOperationGuard.allowsIdentityResult(captured, latest(), 1, 2, account, null));
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), account, account.session));
    }

    @Test public void controlFrameUsesOnlyTheValidatedBindingAndRejectsAccountOrSessionReplacement() {
        BridgeConfig captured = publish(configured());
        RuntimeAccount account = account("first");
        captured.runtimeAccount = account;
        AccountSession stream = account.session;
        // This pure check needs no WeChat classes or main looper to reply to a ping.
        assertTrue(ConfigOperationGuard.allowsStream(captured, latest(), account, stream));
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), account("second"), stream));
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), null, stream));
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), account, null));
        account.session = new AccountSession("installation", "replacement-session", 2);
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), account, stream));
    }

    @Test public void changedCredentialsRejectControlFramesEvenForTheSameBoundAccount() {
        BridgeConfig captured = publish(configured());
        RuntimeAccount account = account("first");
        captured.runtimeAccount = account;
        Properties changed = configured();
        changed.setProperty("api_key", "replacement-key");
        publish(changed);
        assertFalse(ConfigOperationGuard.allowsStream(captured, latest(), account, account.session));
    }

    private static RuntimeAccount account(String name) {
        RuntimeAccount account = new RuntimeAccount("wxid_" + name, "", "/data/user/999/com.tencent.mm/MicroMsg/" + name + "/EnMicroMsg.db");
        account.session = new AccountSession("installation", "session-" + name, 1);
        return account;
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
