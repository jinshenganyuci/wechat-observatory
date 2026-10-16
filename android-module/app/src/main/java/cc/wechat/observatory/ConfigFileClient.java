package cc.wechat.observatory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import cc.wechat.observatory.config.BridgeConfig;
import cc.wechat.observatory.util.BridgeLogger;

/** Finite file operations inside WeChat; no connection to the module process. */
final class ConfigFileClient {
    private static final long TIMEOUT_MS = 15000L;
    private static ConfigFileClient shared;
    private static boolean removed;
    private final Context context;
    private final Handler main;
    private final Executor worker;
    private final Store store;
    private Operation active;

    interface Callback { void complete(Properties settings, String error); }
    interface Store {
        Properties call(Context context, boolean loading, boolean needsSession, Map<String, String> values) throws Exception;
    }

    static ConfigFileClient get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main thread required");
        if (shared == null) {
            Context app = context.getApplicationContext();
            if (app == null) throw new IllegalStateException("application context unavailable");
            shared = new ConfigFileClient(app, new Handler(Looper.getMainLooper()), runnable -> {
                Thread thread = new Thread(runnable, "Observatory configuration file");
                thread.setDaemon(true);
                thread.start();
            }, ConfigFileClient::readOrWrite);
        }
        return shared;
    }

    ConfigFileClient(Context context, Handler main, Executor worker, Store store) {
        this.context = context;
        this.main = main;
        this.worker = worker;
        this.store = store;
    }

    static Properties readOrWrite(Context context, boolean loading, boolean needsSession,
                                           Map<String, String> values) throws Exception {
        Properties snapshot;
        if (loading) snapshot = needsSession ? WeChatConfigStore.loadForRuntime(context) : WeChatConfigStore.read(context);
        else {
            Properties settings = new Properties();
            try {
                settings.putAll(values.isEmpty() ? new LinkedHashMap<>() : ConfigWriteContract.normalize(values));
                snapshot = WeChatConfigStore.saveAndLoad(context, settings, needsSession);
            } finally { settings.clear(); }
        }
        // An absent/deleted file is authoritative. Do not create a new identity
        // or reserve a session until the user saves a new configuration.
        if (snapshot.isEmpty()) return snapshot;
        Properties previous = BridgeConfig.readSettingsForEditor(null);
        try {
            if (!needsSession && !previous.getProperty("__installation_id", "").equals(
                    snapshot.getProperty("__installation_id", ""))) {
                // A recreated file starts a new installation.
                // Reserve from that file before accepting its configuration.
                snapshot.clear();
                snapshot = WeChatConfigStore.loadForRuntime(context);
            }
            return snapshot;
        } finally { previous.clear(); }
    }

    boolean isBusy() { requireMain(); return active != null; }

    boolean execute(boolean loading, boolean needsSession, Map<String, String> values, Callback callback) {
        requireMain();
        if (removed) {
            callback.complete(null, "模块已卸载，请重新安装并重启微信。");
            return false;
        }
        if (active != null) return false;
        Operation operation = new Operation(loading, needsSession, values, callback);
        active = operation;
        operation.start();
        return true;
    }

    static void moduleRemoved() {
        removed = true;
        if (shared != null) shared.cancel();
    }

    void cancel() {
        requireMain();
        if (active != null) active.finish(null, null, true);
    }

    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("main thread required");
    }

    private final class Operation {
        final boolean loading;
        final boolean needsSession;
        final Map<String, String> values;
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        Callback callback;
        volatile boolean completed;
        boolean inFlight;
        final Runnable timeout = () -> finish(null,
                "配置文件操作超时，结果未确认。请稍后重新打开网关读取配置。", false);

        Operation(boolean loading, boolean needsSession, Map<String, String> values, Callback callback) {
            this.loading = loading;
            this.needsSession = needsSession;
            this.values = new LinkedHashMap<>(values);
            this.callback = callback;
        }

        void start() {
            main.postDelayed(timeout, TIMEOUT_MS);
            inFlight = true;
            try {
                worker.execute(() -> {
                    Properties response = null;
                    String error = null;
                    try {
                        if (!completed && SystemClock.elapsedRealtime() < deadline) {
                            response = store.call(context, loading, needsSession, values);
                        } else error = "配置操作已过期，请重试。";
                    } catch (Exception failure) {
                        BridgeLogger.log("private configuration " + (loading ? "read" : "write")
                                + " failed: " + failure.getClass().getSimpleName());
                        error = "无法读写微信内的网关配置，请检查存储空间后重新打开网关重试。";
                    } finally { values.clear(); }
                    Properties result = response;
                    String message = error;
                    main.post(() -> {
                        inFlight = false;
                        if (completed) {
                            if (result != null) result.clear();
                            if (active == this) active = null;
                        } else finish(result, message, false);
                    });
                });
            } catch (RuntimeException rejected) {
                inFlight = false;
                finish(null, "暂时无法读写配置，请稍后重试。", false);
            }
        }

        void finish(Properties response, String error, boolean silent) {
            if (completed) {
                if (response != null) response.clear();
                return;
            }
            completed = true;
            main.removeCallbacks(timeout);
            // A blocked system file operation may still complete. Keep the slot
            // until its worker returns so an old save cannot overtake a new one.
            if (!inFlight) {
                values.clear();
                if (active == this) active = null;
            }
            Callback target = callback;
            callback = null;
            try { if (!silent && target != null) target.complete(response, error); }
            catch (RuntimeException ignored) { }
            finally { if (response != null) response.clear(); }
        }
    }
}
