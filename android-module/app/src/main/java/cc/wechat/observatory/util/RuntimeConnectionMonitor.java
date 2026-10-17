package cc.wechat.observatory.util;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Process-local observations only: no service, wake lock, alarms, traffic or settings changes.
 * Delivery depends on Android scheduling this process. These events cannot wake a frozen app.
 */
public final class RuntimeConnectionMonitor implements AutoCloseable {
    static final long MIN_RECONNECT_INTERVAL_MS = 5000L;
    private static RuntimeConnectionMonitor shared;
    private final Context context;
    private final Handler main;
    private final Clock clock;
    private final Probe probe;
    private volatile Callback callback;
    private volatile boolean closed;
    private volatile boolean receiverRegistered;
    private volatile String networkMonitoring = "not_started";
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean started;
    private Object currentDefault;
    private boolean defaultKnown;
    private final LinkedHashSet<String> pendingReasons = new LinkedHashSet<>();
    private Snapshot pendingSnapshot;
    private boolean reconnectPosted;
    private long lastReconnectElapsed = -1L;
    private final Runnable deliver = this::deliverPending;
    private final Object sampleLock = new Object();
    private long previousElapsed = -1L;
    private long previousUptime;
    private long lastSleepGap;
    private long lastSleepObservedAt;
    private String lastEvent = "install";

    public interface Callback {
        /** Runs on the main thread. The caller may close its old socket and request reconnect. */
        void onReconnectRequested(String reason, Snapshot snapshot);
    }

    /** Idempotent for this process; never retains the supplied Activity or other wrapper context. */
    public static synchronized RuntimeConnectionMonitor install(Context context, Callback callback) {
        if (context == null || callback == null) throw new IllegalArgumentException("monitor dependencies missing");
        Context application = context.getApplicationContext();
        if (application == null) throw new IllegalArgumentException("application context unavailable");
        if (shared == null || shared.closed) {
            shared = new RuntimeConnectionMonitor(application, new Handler(Looper.getMainLooper()),
                    new Clock() {
                        public long elapsed() { return SystemClock.elapsedRealtime(); }
                        public long uptime() { return SystemClock.uptimeMillis(); }
                    }, RuntimeConnectionMonitor::readState, callback);
            shared.runOnMain(shared::start);
        } else shared.callback = callback;
        return shared;
    }

    interface Clock { long elapsed(); long uptime(); }
    interface Probe { State read(Context context); }

    RuntimeConnectionMonitor(Context context, Handler main, Clock clock, Probe probe, Callback callback) {
        this.context = context;
        this.main = main;
        this.clock = clock;
        this.probe = probe;
        this.callback = callback;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (intent != null) broadcast(intent.getAction());
        }
    };

    void start() {
        if (started || closed) return;
        started = true;
        State initial = state();
        currentDefault = initial.activeNetwork;
        defaultKnown = initial.networkKnown;
        sample(initial, "install");
        boolean networkPermission = hasNetworkPermission(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        // API 23 has no default-network callback. A dynamic receiver still
        // works; compare active-network identity instead of every broadcast.
        if (Build.VERSION.SDK_INT == 23 && networkPermission) filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
            } else context.registerReceiver(receiver, filter, null, main);
            receiverRegistered = true;
        } catch (RuntimeException unavailable) { receiverRegistered = false; }
        if (!networkPermission) { networkMonitoring = "permission_missing"; return; }
        if (Build.VERSION.SDK_INT == 23) {
            networkMonitoring = receiverRegistered ? "connectivity_broadcast" : "registration_failed";
            return;
        }
        try {
            connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) { networkMonitoring = "service_unavailable"; return; }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { networkAvailable(network); }
                @Override public void onLost(Network network) { networkLost(network); }
                // Capability/link-property churn is deliberately not a reconnect trigger.
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
            networkMonitoring = "default_callback";
        } catch (RuntimeException unavailable) {
            networkMonitoring = "registration_failed";
            networkCallback = null;
        }
    }

    void broadcast(String action) {
        runOnMain(() -> {
            if (closed) return;
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                State state = state();
                if (state.networkKnown) defaultChanged(state.activeNetwork);
                return;
            }
            String event;
            if (Intent.ACTION_SCREEN_ON.equals(action)) event = "screen_on";
            else if (Intent.ACTION_SCREEN_OFF.equals(action)) event = "screen_off";
            else if (Intent.ACTION_USER_PRESENT.equals(action)) event = "user_present";
            else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) event = "device_idle_changed";
            else return;
            State state = state();
            Snapshot snapshot = sample(state, event);
            if ("screen_on".equals(event) && !Boolean.FALSE.equals(state.interactive)) request(event, snapshot);
            else if ("user_present".equals(event)) request(event, snapshot);
            // The corresponding entry event might have been deferred while
            // the process was frozen; a delivered exit still requests recovery.
            else if ("device_idle_changed".equals(event) && Boolean.FALSE.equals(state.deviceIdle)) request("device_idle_exit", snapshot);
        });
    }

    void networkAvailable(Object network) {
        if (network != null) runOnMain(() -> defaultChanged(network));
    }

    void networkLost(Object network) {
        runOnMain(() -> {
            // Losing an old default after its replacement must not tear down
            // the replacement's new connection.
            if (!closed && defaultKnown && network != null && Objects.equals(currentDefault, network)) defaultChanged(null);
        });
    }

    private void defaultChanged(Object network) {
        if (closed || defaultKnown && Objects.equals(currentDefault, network)) return;
        String reason = network == null ? "network_lost"
                : currentDefault == null ? "network_available" : "default_network_changed";
        currentDefault = network;
        defaultKnown = true;
        request(reason, sample(state(), reason));
    }

    private void request(String reason, Snapshot snapshot) {
        pendingReasons.add(reason);
        pendingSnapshot = snapshot;
        long remaining = lastReconnectElapsed < 0 ? 0
                : MIN_RECONNECT_INTERVAL_MS - Math.max(0L, clock.elapsed() - lastReconnectElapsed);
        if (remaining <= 0) {
            main.removeCallbacks(deliver);
            reconnectPosted = false;
            deliverPending();
        } else if (!reconnectPosted) {
            reconnectPosted = true;
            // One trailing delivery for this burst, never a recurring timer.
            main.postDelayed(deliver, remaining);
        }
    }

    private void deliverPending() {
        reconnectPosted = false;
        if (closed || pendingReasons.isEmpty()) return;
        long remaining = lastReconnectElapsed < 0 ? 0
                : MIN_RECONNECT_INTERVAL_MS - Math.max(0L, clock.elapsed() - lastReconnectElapsed);
        if (remaining > 0) {
            reconnectPosted = true;
            main.postDelayed(deliver, remaining);
            return;
        }
        String reason = TextUtils.join(",", pendingReasons);
        Snapshot snapshot = pendingSnapshot;
        pendingReasons.clear();
        pendingSnapshot = null;
        lastReconnectElapsed = clock.elapsed();
        Callback target = callback;
        if (target != null) {
            try { target.onReconnectRequested(reason, snapshot); }
            catch (RuntimeException ignored) { /* Monitoring must not crash the hooked process. */ }
        }
    }

    /** On-demand local diagnostics; does not request reconnect or send network traffic. */
    public Snapshot snapshot() { return sample(state(), null); }

    private State state() {
        try { return probe.read(context); }
        catch (RuntimeException unavailable) { return new State(null, null, null, false, null, "unknown:error"); }
    }

    private Snapshot sample(State state, String event) {
        synchronized (sampleLock) {
            long elapsed = clock.elapsed();
            long uptime = clock.uptime();
            long interval = previousElapsed < 0 ? 0 : Math.max(0L, elapsed - previousElapsed);
            long sleep = previousElapsed < 0 ? 0 : Math.max(0L, interval - Math.max(0L, uptime - previousUptime));
            previousElapsed = elapsed;
            previousUptime = uptime;
            if (sleep >= 1000L) { lastSleepGap = sleep; lastSleepObservedAt = elapsed; }
            if (event != null) lastEvent = event;
            return new Snapshot(state.interactive, state.deviceIdle, state.batteryOptimizationIgnored,
                    state.networkState, elapsed, uptime, interval, sleep, lastSleepGap, lastSleepObservedAt,
                    lastEvent, receiverRegistered, networkMonitoring);
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == main.getLooper()) runnable.run();
        else main.post(runnable);
    }

    @Override public void close() {
        closed = true;
        callback = null;
        synchronized (RuntimeConnectionMonitor.class) { if (shared == this) shared = null; }
        runOnMain(() -> {
            main.removeCallbacks(deliver);
            pendingReasons.clear();
            pendingSnapshot = null;
            if (receiverRegistered) {
                try { context.unregisterReceiver(receiver); }
                catch (RuntimeException ignored) { }
                receiverRegistered = false;
            }
            if (networkCallback != null && connectivity != null) {
                try { connectivity.unregisterNetworkCallback(networkCallback); }
                catch (RuntimeException ignored) { }
                networkCallback = null;
            }
            networkMonitoring = "closed";
        });
    }

    static final class State {
        final Boolean interactive;
        final Boolean deviceIdle;
        final Boolean batteryOptimizationIgnored;
        final boolean networkKnown;
        final Object activeNetwork;
        final String networkState;
        State(Boolean interactive, Boolean deviceIdle, Boolean ignored, boolean known, Object network, String state) {
            this.interactive = interactive;
            this.deviceIdle = deviceIdle;
            this.batteryOptimizationIgnored = ignored;
            this.networkKnown = known;
            this.activeNetwork = network;
            this.networkState = state;
        }
    }

    private static boolean hasNetworkPermission(Context context) {
        try { return context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }

    private static State readState(Context context) {
        Boolean interactive = null, idle = null, ignored = null;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                interactive = power.isInteractive();
                idle = power.isDeviceIdleMode();
                ignored = power.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        } catch (RuntimeException unavailable) { }
        if (!hasNetworkPermission(context)) return new State(interactive, idle, ignored, false, null, "unknown:permission");
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return new State(interactive, idle, ignored, false, null, "unknown:service");
            Network network = connectivity.getActiveNetwork();
            if (network == null) return new State(interactive, idle, ignored, true, null, "none");
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            String status = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? "validated" : "available_unvalidated";
            // No SSID, IP address, network identifier or user data is collected.
            return new State(interactive, idle, ignored, true, network, status);
        } catch (RuntimeException unavailable) { return new State(interactive, idle, ignored, false, null, "unknown:error"); }
    }

    public static final class Snapshot {
        public final Boolean interactive, deviceIdle, batteryOptimizationIgnored;
        public final String networkState, lastEvent, networkMonitoring;
        public final long elapsedRealtimeMs, uptimeMs, sampleIntervalMs, sleepGapMs;
        public final long lastSleepGapMs, lastSleepObservedAtElapsedMs;
        public final boolean receiverRegistered;
        Snapshot(Boolean interactive, Boolean idle, Boolean ignored, String network, long elapsed, long uptime,
                 long interval, long sleep, long lastSleep, long lastSleepAt, String event, boolean receiver, String monitoring) {
            this.interactive = interactive;
            this.deviceIdle = idle;
            this.batteryOptimizationIgnored = ignored;
            this.networkState = network;
            this.elapsedRealtimeMs = elapsed;
            this.uptimeMs = uptime;
            this.sampleIntervalMs = interval;
            this.sleepGapMs = sleep;
            this.lastSleepGapMs = lastSleep;
            this.lastSleepObservedAtElapsedMs = lastSleepAt;
            this.lastEvent = event;
            this.receiverRegistered = receiver;
            this.networkMonitoring = monitoring;
        }

        /** Clock gaps measure device deep sleep, not whether Android froze this process. */
        public String diagnostic() {
            return "interactive=" + interactive + " idle=" + deviceIdle + " batteryExempt=" + batteryOptimizationIgnored
                    + " network=" + networkState + " networkMonitor=" + networkMonitoring + " receiver=" + receiverRegistered
                    + " event=" + lastEvent + " elapsedMs=" + elapsedRealtimeMs + " uptimeMs=" + uptimeMs
                    + " sampleIntervalMs=" + sampleIntervalMs + " sleepGapMs=" + sleepGapMs
                    + " lastSleepGapMs=" + lastSleepGapMs + " lastSleepAtMs=" + lastSleepObservedAtElapsedMs;
        }
    }
}
