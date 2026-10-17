package cc.wechat.observatory.util;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RuntimeConnectionMonitorTest {
    private RecordingContext context;
    private FakeClock clock;
    private MutableProbe probe;
    private RuntimeConnectionMonitor monitor;
    private final List<String> reasons = new ArrayList<>();
    private final List<RuntimeConnectionMonitor.Snapshot> snapshots = new ArrayList<>();

    @Before public void setUp() {
        context = new RecordingContext();
        clock = new FakeClock();
        probe = new MutableProbe();
        monitor = newMonitor(context);
        monitor.start();
    }

    @After public void tearDown() {
        monitor.close();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void screenAndUnlockBurstIsThrottledWithoutRecurringWork() {
        probe.interactive = false;
        probe.idle = true;
        context.send(Intent.ACTION_SCREEN_OFF);
        context.send(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        assertTrue(reasons.isEmpty());

        probe.interactive = true;
        probe.idle = false;
        context.send(Intent.ACTION_SCREEN_ON);
        context.send(Intent.ACTION_USER_PRESENT);
        context.send(Intent.ACTION_USER_PRESENT);
        context.send(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        assertEquals(1, reasons.size());
        assertEquals("screen_on", reasons.get(0));

        advance(5000L);
        assertEquals(2, reasons.size());
        assertEquals("user_present,device_idle_exit", reasons.get(1));
        advance(60000L);
        assertEquals("monitor must not poll or keep scheduling reconnect", 2, reasons.size());
    }

    @Test public void idleExitRecoversEvenWhenItsEntryBroadcastWasNotDelivered() {
        probe.idle = false;
        context.send(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        assertEquals(1, reasons.size());
        assertEquals("device_idle_exit", reasons.get(0));
    }

    @Test public void networkTransitionsIgnoreDuplicatesAndLateLossOfOldDefault() {
        Object wifi = new Object();
        Object cellular = new Object();
        monitor.networkAvailable(wifi);
        monitor.networkAvailable(wifi);
        monitor.networkAvailable(cellular);
        monitor.networkLost(wifi);
        assertEquals(1, reasons.size());
        advance(5000L);
        assertEquals(2, reasons.size());
        assertEquals("network_available", reasons.get(0));
        assertEquals("default_network_changed", reasons.get(1));

        monitor.networkLost(cellular);
        monitor.networkLost(cellular);
        advance(5000L);
        assertEquals(3, reasons.size());
        assertEquals("network_lost", reasons.get(2));
        monitor.networkAvailable(cellular);
        advance(5000L);
        assertEquals(4, reasons.size());
        assertEquals("network_available", reasons.get(3));
    }

    @Test public void networkCallbackFromAnotherThreadIsDeliveredOnMain() throws Exception {
        Thread networkThread = new Thread(() -> monitor.networkAvailable(new Object()), "test-connectivity");
        networkThread.start();
        networkThread.join(2000L);
        assertFalse(networkThread.isAlive());
        assertTrue(reasons.isEmpty());

        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, reasons.size());
        assertEquals("network_available", reasons.get(0));
    }

    @Test public void clockGapMeasuresDeepSleepAndKeepsLastObservation() {
        clock.elapsed += 120000L;
        clock.uptime += 5000L;
        RuntimeConnectionMonitor.Snapshot first = monitor.snapshot();
        assertEquals(120000L, first.sampleIntervalMs);
        assertEquals(115000L, first.sleepGapMs);
        assertEquals(115000L, first.lastSleepGapMs);
        assertEquals(clock.elapsed, first.lastSleepObservedAtElapsedMs);

        clock.elapsed += 1000L;
        clock.uptime += 1000L;
        RuntimeConnectionMonitor.Snapshot next = monitor.snapshot();
        assertEquals(0L, next.sleepGapMs);
        assertEquals(115000L, next.lastSleepGapMs);
        assertTrue(next.diagnostic().contains("lastSleepGapMs=115000"));
        assertTrue(reasons.isEmpty());
    }

    @Test public void wakeEventUsesElapsedTimeAndReplacesPreSleepTrailingDelivery() {
        context.send(Intent.ACTION_SCREEN_ON);
        context.send(Intent.ACTION_USER_PRESENT);
        clock.elapsed += 60000L;
        clock.uptime += 10L;

        context.send(Intent.ACTION_SCREEN_ON);

        assertEquals(2, reasons.size());
        assertEquals("user_present,screen_on", reasons.get(1));
        assertEquals(59990L, snapshots.get(1).sleepGapMs);
        advance(5000L);
        assertEquals("the old delayed callback must have been cancelled", 2, reasons.size());
    }

    @Test public void closeCancelsPendingReconnectAndUnregistersOnlyOnce() {
        context.send(Intent.ACTION_SCREEN_ON);
        context.send(Intent.ACTION_USER_PRESENT);
        monitor.close();
        monitor.close();
        advance(10000L);
        context.send(Intent.ACTION_USER_PRESENT);
        monitor.networkAvailable(new Object());

        assertEquals(1, reasons.size());
        assertEquals(1, context.unregistered);
        assertEquals("closed", monitor.snapshot().networkMonitoring);
    }

    @Test public void installUsesApplicationContextAndDoesNotDuplicateReceiversWithoutNetworkPermission() {
        RecordingContext application = new RecordingContext();
        Context wrapper = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return application; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                                     String permission, Handler handler) {
                fail("must register using the application context");
                return null;
            }
        };
        RuntimeConnectionMonitor installed = RuntimeConnectionMonitor.install(wrapper, (reason, snapshot) -> { });
        try {
            RuntimeConnectionMonitor again = RuntimeConnectionMonitor.install(wrapper, (reason, snapshot) -> { });
            assertSame(installed, again);
            assertEquals(1, application.registered);
            RuntimeConnectionMonitor.Snapshot snapshot = installed.snapshot();
            assertTrue(snapshot.receiverRegistered);
            assertEquals("permission_missing", snapshot.networkMonitoring);
            assertEquals("unknown:permission", snapshot.networkState);
        } finally { installed.close(); }
        assertEquals(1, application.unregistered);
    }

    @Test @Config(sdk = 23)
    public void android23ConnectivityBroadcastOnlyTriggersOnChangedActiveNetwork() {
        monitor.close();
        context = new RecordingContext();
        context.networkPermission = true;
        monitor = newMonitor(context);
        monitor.start();
        assertTrue(context.filter.hasAction(ConnectivityManager.CONNECTIVITY_ACTION));
        assertEquals("connectivity_broadcast", monitor.snapshot().networkMonitoring);

        context.send(ConnectivityManager.CONNECTIVITY_ACTION);
        assertTrue(reasons.isEmpty());
        probe.network = new Object();
        context.send(ConnectivityManager.CONNECTIVITY_ACTION);
        context.send(ConnectivityManager.CONNECTIVITY_ACTION);
        assertEquals(1, reasons.size());
        probe.network = null;
        context.send(ConnectivityManager.CONNECTIVITY_ACTION);
        advance(5000L);
        assertEquals(2, reasons.size());
        assertEquals("network_lost", reasons.get(1));
    }

    @Test @Config(sdk = 33)
    public void android33RegistersOnlyNonExportedSystemEventReceiver() {
        assertEquals(Context.RECEIVER_NOT_EXPORTED, context.flags);
        assertTrue(context.filter.hasAction(Intent.ACTION_SCREEN_ON));
        assertTrue(context.filter.hasAction(Intent.ACTION_USER_PRESENT));
        assertFalse(context.filter.hasAction(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Test public void diagnosticsContainNoNetworkIdentityAndIgnoreUnrelatedBroadcasts() {
        probe.network = new Object() { @Override public String toString() { return "dummy-private-network-id"; } };
        context.send("example.unrelated.action");
        RuntimeConnectionMonitor.Snapshot snapshot = monitor.snapshot();
        assertFalse(snapshot.diagnostic().contains("dummy-private-network-id"));
        assertEquals(Boolean.TRUE, snapshot.interactive);
        assertEquals(Boolean.FALSE, snapshot.deviceIdle);
        assertEquals(Boolean.FALSE, snapshot.batteryOptimizationIgnored);
        assertTrue(reasons.isEmpty());
    }

    private RuntimeConnectionMonitor newMonitor(RecordingContext owner) {
        return new RuntimeConnectionMonitor(owner, new Handler(Looper.getMainLooper()), clock, probe,
                (reason, snapshot) -> {
                    assertSame(Looper.getMainLooper(), Looper.myLooper());
                    reasons.add(reason);
                    snapshots.add(snapshot);
                });
    }

    private void advance(long millis) {
        clock.elapsed += millis;
        clock.uptime += millis;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static final class FakeClock implements RuntimeConnectionMonitor.Clock {
        long elapsed = 10000L, uptime = 10000L;
        public long elapsed() { return elapsed; }
        public long uptime() { return uptime; }
    }

    private static final class MutableProbe implements RuntimeConnectionMonitor.Probe {
        boolean interactive = true, idle;
        Object network;
        public RuntimeConnectionMonitor.State read(Context ignored) {
            return new RuntimeConnectionMonitor.State(interactive, idle, false, true, network,
                    network == null ? "none" : "validated");
        }
    }

    private static final class RecordingContext extends ContextWrapper {
        BroadcastReceiver receiver;
        IntentFilter filter;
        int registered, unregistered, flags;
        boolean networkPermission;
        RecordingContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkSelfPermission(String permission) {
            return networkPermission && Manifest.permission.ACCESS_NETWORK_STATE.equals(permission)
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                                 String permission, Handler handler) {
            this.receiver = receiver;
            this.filter = filter;
            registered++;
            assertSame(Looper.getMainLooper(), handler.getLooper());
            return null;
        }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                                 String permission, Handler handler, int flags) {
            this.flags = flags;
            return registerReceiver(receiver, filter, permission, handler);
        }
        @Override public void unregisterReceiver(BroadcastReceiver receiver) {
            assertSame(this.receiver, receiver);
            unregistered++;
        }
        void send(String action) { receiver.onReceive(this, new Intent(action)); }
    }
}
