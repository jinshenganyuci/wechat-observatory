package cc.wechat.observatory;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfigFileClientTest {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private ConfigFileClient client;
    private int calls;
    private int replies;
    private Map<String, String> passed;
    private Properties response;
    private Properties delivered;
    private String error;

    @Before public void prepare() {
        calls = 0;
        replies = 0;
        client = make((context, loading, needsSession, values) -> {
            calls++;
            passed = values;
            assertEquals("dummy-key", values.get("api_key"));
            response = new Properties();
            response.setProperty("api_key", values.get("api_key"));
            return response;
        }, pending::add);
    }

    private ConfigFileClient make(ConfigFileClient.Store store, Executor worker) {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                throw new AssertionError("Must not start the module service");
            }
            @Override public void startActivity(Intent intent) {
                throw new AssertionError("Must not open the module");
            }
        };
        return new ConfigFileClient(context, new Handler(Looper.getMainLooper()), worker, store);
    }

    private void execute() {
        Map<String, String> supplied = new LinkedHashMap<>();
        supplied.put("api_key", "dummy-key");
        assertTrue(client.execute(false, false, supplied, (settings, message) -> {
            replies++;
            delivered = new Properties();
            if (settings != null) delivered.putAll(settings);
            error = message;
        }));
        supplied.clear();
    }

    @Test public void copiedInputsAndOutputsAreClearedAfterFiniteOperation() {
        execute();
        assertEquals(0, calls);
        assertTrue(client.isBusy());
        pending.remove().run();
        assertEquals(0, replies);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls);
        assertEquals(1, replies);
        assertEquals("dummy-key", delivered.getProperty("api_key"));
        assertTrue(passed.isEmpty());
        assertTrue(response.isEmpty());
        assertNull(error);
        assertFalse(client.isBusy());
    }

    @Test public void timedOutQueuedSaveNeverTouchesFileAndPreventsOvertaking() {
        execute();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(15001));
        assertEquals(1, replies);
        assertNotNull(error);
        assertTrue(client.isBusy());
        assertFalse(client.execute(false, false, Collections.emptyMap(), (a, b) -> fail()));
        pending.remove().run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, calls);
        assertEquals(1, replies);
        assertFalse(client.isBusy());
    }

    @Test public void timeoutDiscardsAlreadyCompletedResponseUntilWorkerDelivery() {
        execute();
        pending.remove().run();
        // cancel acts like uninstall before the queued result reaches the UI.
        client.cancel();
        assertTrue(client.isBusy());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls);
        assertEquals(0, replies);
        assertTrue(response.isEmpty());
        assertFalse(client.isBusy());
    }

    @Test public void cancelledQueuedSaveDoesNotRun() {
        execute();
        client.cancel();
        pending.remove().run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, calls);
        assertEquals(0, replies);
        assertFalse(client.isBusy());
    }

    @Test public void failureDoesNotExposeExceptionWithCredentials() {
        client = make((context, loading, needsSession, values) -> {
            throw new java.io.IOException("secret-api-key-at-sensitive-url");
        }, pending::add);
        execute();
        pending.remove().run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, replies);
        assertFalse(error.contains("secret-api-key"));
        assertTrue(delivered.isEmpty());
        assertFalse(client.isBusy());
    }

    @Test public void rejectedWorkerReleasesOperation() {
        client = make((a, b, c, d) -> { fail(); return null; }, task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        execute();
        assertEquals(1, replies);
        assertNotNull(error);
        assertFalse(client.isBusy());
    }

    @Test public void overlappingRequestCannotOverwriteFirstSave() {
        execute();
        assertFalse(client.execute(false, false, Collections.emptyMap(), (a, b) -> fail()));
        assertEquals(1, pending.size());
        pending.remove().run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, replies);
    }
}
