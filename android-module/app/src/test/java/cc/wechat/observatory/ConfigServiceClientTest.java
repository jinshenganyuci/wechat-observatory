package cc.wechat.observatory;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfigServiceClientTest {
    private static final ComponentName SERVICE = new ComponentName(
            ConfigWriteContract.MODULE_PACKAGE, ConfigBinderProtocol.SERVICE_CLASS);
    private static final long AFTER_TIMEOUT_MS = 15001L;

    private BindingContext context;
    private QueuedExecutor worker;
    private RecordingExchange exchange;
    private CallbackProbe callback;
    private ConfigServiceClient client;

    @Before public void setUp() {
        context = new BindingContext();
        worker = new QueuedExecutor();
        exchange = new RecordingExchange();
        callback = new CallbackProbe();
        client = client(exchange, worker);
    }

    @Test public void readSuccessDeliversOneSnapshotAndUnbindsOnce() {
        assertTrue(client.execute(true, true, Collections.emptyMap(), callback));
        assertTrue(client.isBusy());
        assertEquals(SERVICE, context.intent.getComponent());
        assertEquals(Context.BIND_AUTO_CREATE, context.flags);
        assertNull("credentials must not travel in the binding intent", context.intent.getExtras());

        connected();
        connected(); // Android can reconnect; the operation still issues only one RPC.
        assertEquals(1, worker.size());
        worker.runNext();
        assertTrue(exchange.request.loading);
        assertTrue(exchange.request.needsSession);
        assertTrue(exchange.request.values.isEmpty());
        assertEquals(0, callback.count());
        idleMain();

        assertEquals(1, callback.count());
        assertNull(callback.errors.get(0));
        assertEquals(ConfigBinderProtocol.OK, callback.statuses.get(0));
        assertEquals("dummy-key", callback.values.get(0).get("api_key"));
        assertTrue(exchange.response.values.isEmpty());
        assertFalse(client.isBusy());
        assertEquals(1, context.unbinds);
        assertEquals(1, exchange.calls);

        advancePastTimeout();
        context.connection.onServiceDisconnected(SERVICE);
        assertEquals(1, callback.count());
        assertEquals(1, context.unbinds);
    }

    @Test public void saveSuccessClearsRequestAndResponseAndReleasesSlot() {
        Map<String, String> supplied = saveValues();
        assertTrue(client.execute(false, false, supplied, callback));
        supplied.clear();
        connected();
        worker.runNext();
        idleMain();

        assertFalse(exchange.request.loading);
        assertFalse(exchange.request.needsSession);
        assertEquals("dummy-key", exchange.received.get("api_key"));
        assertTrue(exchange.request.values.isEmpty());
        assertTrue(exchange.response.values.isEmpty());
        assertEquals(1, callback.count());
        assertNull(callback.errors.get(0));
        assertEquals(1, context.unbinds);
        assertFalse(client.isBusy());
    }

    @Test public void refusedBindReportsFailureAndDoesNotWaitForTimeout() {
        context.bindResult = false;
        context.unbindFailure = new IllegalArgumentException("not registered");

        assertTrue(client.execute(false, false, saveValues(), callback));

        assertFailedAndReleased();
        assertEquals(0, exchange.calls);
        assertEquals(0, worker.size());
        advancePastTimeout();
        assertEquals(1, callback.count());
        assertEquals(1, context.unbinds);
    }

    @Test public void bindExceptionReportsFailureWithoutLeakingExceptionText() {
        context.bindFailure = new SecurityException("dummy-sensitive-detail");

        assertTrue(client.execute(true, true, Collections.emptyMap(), callback));

        assertFailedAndReleased();
        assertFalse(callback.errors.get(0).contains("dummy-sensitive-detail"));
        assertEquals(0, worker.size());
        advancePastTimeout();
        assertEquals(1, callback.count());
    }

    @Test public void missingConnectionTimesOutAndIgnoresALateConnection() {
        assertTrue(client.execute(true, true, Collections.emptyMap(), callback));

        advancePastTimeout();

        assertFailedAndReleased();
        assertTrue(callback.errors.get(0).contains("超时"));
        connected();
        assertEquals(0, worker.size());
        assertEquals(0, exchange.calls);
        assertEquals(1, callback.count());
        assertEquals(1, context.unbinds);
    }

    @Test public void inFlightTimeoutRetainsBusySlotUntilRpcReturnsAndDropsLateSuccess() throws Exception {
        GatedExchange gate = new GatedExchange();
        client = client(gate, worker);
        assertTrue(client.execute(false, false, saveValues(), callback));
        connected();
        WorkerRun running = worker.startNext();
        try {
            gate.awaitEntered();
            advancePastTimeout();

            assertEquals(1, callback.count());
            assertTrue(callback.errors.get(0).contains("超时"));
            assertTrue("an already-started write must still own the slot", client.isBusy());
            assertFalse(client.execute(false, false, saveValues(), new CallbackProbe()));
            assertEquals(1, context.binds);
            assertEquals(1, context.unbinds);

            gate.release.countDown();
            running.awaitFinished();
            assertTrue("the completion still waits for the main looper", client.isBusy());
            idleMain();

            assertFalse(client.isBusy());
            assertEquals(1, callback.count());
            assertTrue(gate.response.values.isEmpty());
            assertTrue(gate.request.values.isEmpty());
            assertEquals(1, context.unbinds);
        } finally {
            gate.release.countDown();
            running.awaitFinished();
        }
    }

    @Test public void nullBindingBindingDeathAndDisconnectEachFinishOnlyOnce() {
        for (ConnectionEnd end : ConnectionEnd.values()) {
            setUp();
            assertTrue(client.execute(true, true, Collections.emptyMap(), callback));
            end.signal(context.connection);

            assertFailedAndReleased();
            for (ConnectionEnd again : ConnectionEnd.values()) again.signal(context.connection);
            connected();
            advancePastTimeout();
            assertEquals(1, callback.count());
            assertEquals(1, context.unbinds);
            assertEquals(0, worker.size());
        }
    }

    @Test public void disconnectDuringRpcKeepsSlotAndDropsReturnedValues() throws Exception {
        GatedExchange gate = new GatedExchange();
        client = client(gate, worker);
        assertTrue(client.execute(false, false, saveValues(), callback));
        connected();
        WorkerRun running = worker.startNext();
        try {
            gate.awaitEntered();
            context.connection.onServiceDisconnected(SERVICE);
            assertEquals(1, callback.count());
            assertNotNull(callback.errors.get(0));
            assertTrue(client.isBusy());
            assertFalse(client.execute(false, false, saveValues(), new CallbackProbe()));

            gate.release.countDown();
            running.awaitFinished();
            idleMain();

            assertFalse(client.isBusy());
            assertEquals(1, callback.count());
            assertTrue(gate.response.values.isEmpty());
            assertEquals(1, context.unbinds);
        } finally {
            gate.release.countDown();
            running.awaitFinished();
        }
    }

    @Test public void cancelAfterResponseIsEnqueuedSuppressesCallbackAndClearsResponse() {
        assertTrue(client.execute(false, false, saveValues(), callback));
        connected();
        worker.runNext();
        assertEquals(1, exchange.calls);
        assertFalse(exchange.response.values.isEmpty());
        assertEquals(0, callback.count());

        client.cancel();
        client.cancel();
        idleMain();
        advancePastTimeout();

        assertEquals(0, callback.count());
        assertTrue(exchange.response.values.isEmpty());
        assertFalse(client.isBusy());
        assertEquals(1, context.unbinds);
    }

    @Test public void cancelBeforeQueuedWorkerStartsNeverIssuesRpc() {
        assertTrue(client.execute(false, false, saveValues(), callback));
        connected();
        assertEquals(1, worker.size());

        client.cancel();
        worker.runNext();
        idleMain();
        advancePastTimeout();

        assertEquals(0, exchange.calls);
        assertEquals(0, callback.count());
        assertFalse(client.isBusy());
        assertEquals(1, context.unbinds);
    }

    @Test public void exchangeFailureReportsGenericErrorAndClearsRequest() {
        exchange.failure = new IllegalStateException("dummy-sensitive-detail");
        assertTrue(client.execute(false, false, saveValues(), callback));
        connected();
        worker.runNext();
        idleMain();

        assertFailedAndReleased();
        assertFalse(callback.errors.get(0).contains("dummy-sensitive-detail"));
        assertTrue(exchange.request.values.isEmpty());
    }

    private ConfigServiceClient client(ConfigServiceClient.Exchange operation, Executor executor) {
        return new ConfigServiceClient(context, new Handler(Looper.getMainLooper()), executor, operation);
    }

    private void connected() { context.connection.onServiceConnected(SERVICE, new Binder()); }
    private static void idleMain() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void advancePastTimeout() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AFTER_TIMEOUT_MS));
    }

    private void assertFailedAndReleased() {
        assertEquals(1, callback.count());
        assertNotNull(callback.errors.get(0));
        assertFalse(callback.errors.get(0).isEmpty());
        assertNull(callback.statuses.get(0));
        assertFalse(client.isBusy());
        assertEquals(1, context.unbinds);
    }

    private static Map<String, String> saveValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bridge_url", "https://example.invalid/");
        values.put("api_key", "dummy-key");
        return values;
    }

    private static ConfigBinderProtocol.Response success(boolean needsSession) {
        Map<String, String> values = saveValues();
        values.put(ConfigWriteContract.REVISION_KEY, "12345678-1234-4234-8234-123456789abc");
        if (needsSession) {
            values.put("__installation_id", "dummy-installation-1234");
            values.put("__generation_start", "1");
            values.put("__generation_limit", "1000000");
        }
        return ConfigBinderProtocol.success(values, needsSession);
    }

    private static final class BindingContext extends ContextWrapper {
        boolean bindResult = true;
        RuntimeException bindFailure;
        RuntimeException unbindFailure;
        int binds;
        int unbinds;
        int flags;
        Intent intent;
        ServiceConnection connection;

        BindingContext() { super(RuntimeEnvironment.getApplication()); }

        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            this.intent = new Intent(intent);
            this.connection = connection;
            this.flags = flags;
            binds++;
            if (bindFailure != null) throw bindFailure;
            return bindResult;
        }

        @Override public void unbindService(ServiceConnection connection) {
            assertSame(this.connection, connection);
            unbinds++;
            if (unbindFailure != null) throw unbindFailure;
        }
    }

    private static final class CallbackProbe implements ConfigServiceClient.Callback {
        final List<String> statuses = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Map<String, String>> values = new ArrayList<>();

        @Override public void complete(ConfigBinderProtocol.Response response, String error) {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            statuses.add(response == null ? null : response.status);
            errors.add(error);
            values.add(response == null ? Collections.emptyMap() : new LinkedHashMap<>(response.values));
        }

        int count() { return statuses.size(); }
    }

    private static final class RecordingExchange implements ConfigServiceClient.Exchange {
        int calls;
        ConfigBinderProtocol.Request request;
        Map<String, String> received;
        ConfigBinderProtocol.Response response;
        Exception failure;

        @Override public ConfigBinderProtocol.Response call(IBinder binder, ConfigBinderProtocol.Request request)
                throws Exception {
            calls++;
            this.request = request;
            received = new LinkedHashMap<>(request.values);
            if (failure != null) throw failure;
            response = success(request.needsSession);
            return response;
        }
    }

    private static final class GatedExchange implements ConfigServiceClient.Exchange {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile ConfigBinderProtocol.Request request;
        volatile ConfigBinderProtocol.Response response;

        @Override public ConfigBinderProtocol.Response call(IBinder binder, ConfigBinderProtocol.Request request)
                throws Exception {
            this.request = request;
            response = success(request.needsSession);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            return response;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue("the test RPC did not start", entered.await(5, TimeUnit.SECONDS));
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        int size() { return tasks.size(); }
        void runNext() { tasks.removeFirst().run(); }
        WorkerRun startNext() { return new WorkerRun(tasks.removeFirst()); }
    }

    private static final class WorkerRun {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        WorkerRun(Runnable task) {
            thread = new Thread(() -> {
                try { task.run(); }
                catch (Throwable error) { failure.set(error); }
            }, "test-config-rpc");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitFinished() throws InterruptedException {
            thread.join(5000L);
            assertFalse("test RPC did not finish", thread.isAlive());
            if (failure.get() != null) throw new AssertionError("test worker failed", failure.get());
        }
    }

    private enum ConnectionEnd {
        NULL { @Override void signal(ServiceConnection connection) { connection.onNullBinding(SERVICE); } },
        DEAD { @Override void signal(ServiceConnection connection) { connection.onBindingDied(SERVICE); } },
        DISCONNECTED { @Override void signal(ServiceConnection connection) { connection.onServiceDisconnected(SERVICE); } };
        abstract void signal(ServiceConnection connection);
    }
}
