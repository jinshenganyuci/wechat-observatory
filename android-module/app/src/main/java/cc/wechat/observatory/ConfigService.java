package cc.wechat.observatory;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bound only for a configuration operation. The module UID owns all durable state. */
public final class ConfigService extends Service {
    private static final AtomicBoolean CLEANUP_SCHEDULED = new AtomicBoolean();
    interface Clock { long now(); }
    interface Store {
        Properties load(Context context, boolean needsSession) throws IOException;
        ModuleConfigStore.SaveResult save(Context context, Properties settings);
    }
    Clock clock = SystemClock::elapsedRealtime;
    Store store = new Store() {
        @Override public Properties load(Context context, boolean needsSession) throws IOException {
            return needsSession ? ModuleConfigStore.loadForRuntime(context) : ModuleConfigStore.read(context);
        }
        @Override public ModuleConfigStore.SaveResult save(Context context, Properties settings) {
            return ModuleConfigStore.save(context, settings);
        }
    };

    private final Binder binder = new Binder() {
        { attachInterface(null, ConfigBinderProtocol.DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(ConfigBinderProtocol.DESCRIPTOR);
                return true;
            }
            if (code != ConfigBinderProtocol.TRANSACTION_CONFIG || reply == null
                    || (flags & IBinder.FLAG_ONEWAY) != 0) return false;
            ConfigBinderProtocol.Request request = null;
            ConfigBinderProtocol.Response response = null;
            String nonce = "";
            try {
                int callerUid = Binder.getCallingUid();
                if (!allowedCaller(callerUid)) {
                    response = ConfigBinderProtocol.failure(ConfigBinderProtocol.DENIED);
                } else {
                    try {
                        request = ConfigBinderProtocol.readRequest(data);
                        nonce = request.nonce;
                    } catch (RuntimeException invalid) {
                        response = ConfigBinderProtocol.failure(ConfigBinderProtocol.INVALID);
                    }
                    if (request != null) response = perform(callerUid, request);
                }
                ConfigBinderProtocol.writeResponse(reply, nonce, response);
                return true;
            } finally {
                if (request != null) request.clear();
                if (response != null) response.clear();
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // An accidental explicit start must not turn a bound component into a daemon.
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private boolean allowedCaller(int callingUid) {
        try {
            ApplicationInfo wechat = getPackageManager().getApplicationInfo("com.tencent.mm", 0);
            // Full UID comparison also excludes the same package in another Android user.
            return callingUid >= 0 && callingUid == wechat.uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    private ConfigBinderProtocol.Response perform(int callerUid, ConfigBinderProtocol.Request request) {
        Map<String, String> normalized = null;
        Properties settings = new Properties();
        Properties snapshot = null;
        Map<String, String> replyValues = null;
        try {
            if (!request.loading) {
                try {
                    normalized = request.values.isEmpty() ? new LinkedHashMap<>()
                            : ConfigWriteContract.normalize(request.values);
                    settings.putAll(normalized);
                } catch (Exception invalid) {
                    return ConfigBinderProtocol.failure(ConfigBinderProtocol.INVALID);
                }
            }
            // Other entry points synchronize on this class too. A save and its
            // returned snapshot must be one operation, including range reservation.
            synchronized (ModuleConfigStore.class) {
                if (!allowedCaller(callerUid)) return ConfigBinderProtocol.failure(ConfigBinderProtocol.DENIED);
                long remaining = request.deadlineElapsedMs - clock.now();
                if (remaining <= 0) return ConfigBinderProtocol.failure(ConfigBinderProtocol.EXPIRED);
                if (remaining > ConfigBinderProtocol.TIMEOUT_MS) return ConfigBinderProtocol.failure(ConfigBinderProtocol.INVALID);
                String savedRevision = null;
                if (!request.loading) {
                    ModuleConfigStore.SaveResult saved = store.save(this, settings);
                    if (!saved.success) return ConfigBinderProtocol.failure(ConfigBinderProtocol.FAILED);
                    savedRevision = saved.revision;
                }
                snapshot = store.load(this, request.needsSession);
                if (!request.loading && (savedRevision == null
                        || !savedRevision.equals(snapshot.getProperty(ModuleConfigStore.REVISION)))) {
                    return ConfigBinderProtocol.failure(ConfigBinderProtocol.FAILED);
                }
                replyValues = ConfigWriteContract.resultSnapshot(snapshot, request.needsSession);
                ConfigBinderProtocol.Response response = ConfigBinderProtocol.success(replyValues, request.needsSession);
                scheduleLegacyCleanup();
                return response;
            }
        } catch (IOException | RuntimeException failed) {
            // Do not marshal exception messages or partial credentials to the caller.
            return ConfigBinderProtocol.failure(ConfigBinderProtocol.FAILED);
        } finally {
            settings.clear();
            if (normalized != null) normalized.clear();
            if (snapshot != null) snapshot.clear();
            if (replyValues != null) replyValues.clear();
        }
    }

    private void scheduleLegacyCleanup() {
        if (!CLEANUP_SCHEDULED.compareAndSet(false, true)) return;
        Context context = getApplicationContext();
        try {
            new Thread(() -> {
                try { LegacyConfigCleanup.cleanup(context, false); }
                catch (Throwable ignored) { }
            }, "Observatory legacy cleanup").start();
        } catch (RuntimeException unavailable) {
            CLEANUP_SCHEDULED.set(false);
        }
    }
}
