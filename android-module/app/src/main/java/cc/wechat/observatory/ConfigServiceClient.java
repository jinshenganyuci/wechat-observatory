package cc.wechat.observatory;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.Executor;

/** One short-lived binding per operation; no Activity, credentials on disk, or polling. */
final class ConfigServiceClient {
    private static final long TIMEOUT_MS = 15000L;
    private static ConfigServiceClient shared;
    private final Context context;
    private final Handler main;
    private final Executor worker;
    private final Exchange exchange;
    private Operation active;

    interface Callback {
        // The response is cleared after this call; copy only the accepted runtime snapshot.
        void complete(ConfigBinderProtocol.Response response, String error);
    }

    interface Exchange {
        ConfigBinderProtocol.Response call(IBinder binder, ConfigBinderProtocol.Request request) throws Exception;
    }

    static ConfigServiceClient get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main thread required");
        if (shared == null) {
            Context app = context.getApplicationContext();
            if (app == null) throw new IllegalStateException("application context unavailable");
            shared = new ConfigServiceClient(app, new Handler(Looper.getMainLooper()), runnable -> {
                Thread thread = new Thread(runnable, "Observatory config Binder");
                thread.setDaemon(true);
                thread.start();
            }, ConfigBinderProtocol::transact);
        }
        return shared;
    }

    // Dependencies are injectable so timeout/disconnection races can be exercised without a phone.
    ConfigServiceClient(Context context, Handler main, Executor worker, Exchange exchange) {
        this.context = context;
        this.main = main;
        this.worker = worker;
        this.exchange = exchange;
    }

    boolean isBusy() { requireMain(); return active != null; }

    boolean execute(boolean loading, boolean needsSession, Map<String, String> values, Callback callback) {
        requireMain();
        if (active != null) return false;
        ConfigBinderProtocol.Request request = new ConfigBinderProtocol.Request(loading, needsSession,
                values, SystemClock.elapsedRealtime() + TIMEOUT_MS);
        Operation operation = new Operation(request, callback);
        active = operation;
        operation.bind();
        return true;
    }

    static void moduleRemoved() {
        if (shared != null) shared.cancel();
    }

    void cancel() {
        requireMain();
        if (active != null) active.finish(null, "模块已卸载或配置连接已取消。", true);
    }

    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("main thread required");
    }

    private final class Operation implements ServiceConnection {
        private final ConfigBinderProtocol.Request request;
        private Callback callback;
        private boolean bindingAttempted;
        private volatile boolean completed;
        private boolean rpcInFlight;
        private final Runnable timeout = () -> finish(null,
                "配置服务响应超时，读写结果未确认。请重新打开网关设置读取后再试。", false);

        Operation(ConfigBinderProtocol.Request request, Callback callback) {
            this.request = request;
            this.callback = callback;
        }

        void bind() {
            main.postDelayed(timeout, TIMEOUT_MS);
            Intent intent = new Intent().setComponent(new ComponentName(
                    ConfigWriteContract.MODULE_PACKAGE, ConfigBinderProtocol.SERVICE_CLASS));
            bindingAttempted = true;
            try {
                if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                    finish(null, "无法连接配置服务，请确认模块已安装，并允许微信与模块通信。", false);
                }
            } catch (RuntimeException unavailable) {
                finish(null, "配置连接被系统或应用隐藏规则阻止，请允许微信与模块通信。", false);
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (completed || rpcInFlight) return;
            if (binder == null || !ConfigWriteContract.MODULE_PACKAGE.equals(name.getPackageName())
                    || !ConfigBinderProtocol.SERVICE_CLASS.equals(name.getClassName())) {
                finish(null, "配置服务身份异常，未读取或保存配置。", false);
                return;
            }
            rpcInFlight = true;
            try {
                worker.execute(() -> {
                    ConfigBinderProtocol.Response response = null;
                    String error = null;
                    try {
                        if (!completed) response = exchange.call(binder, request);
                    }
                    catch (Exception failure) {
                        // Never include exception text: it can contain configuration values.
                        error = "配置服务连接中断或返回无效数据，读写结果未确认，请重新读取。";
                    } finally { request.clear(); }
                    ConfigBinderProtocol.Response result = response;
                    String message = error;
                    main.post(() -> {
                        rpcInFlight = false;
                        if (completed) {
                            if (result != null) result.clear();
                            if (active == this) active = null;
                            return;
                        }
                        finish(result, message, false);
                    });
                });
            } catch (RuntimeException rejected) {
                rpcInFlight = false;
                finish(null, "暂时无法执行配置操作，请稍后重试。", false);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            finish(null, "配置服务已断开，读写结果未确认，请重新读取。", false);
        }

        @Override public void onBindingDied(ComponentName name) {
            finish(null, "模块已更新或连接失效，请重新打开网关设置。", false);
        }

        @Override public void onNullBinding(ComponentName name) {
            finish(null, "配置服务拒绝了连接，请检查模块安装状态。", false);
        }

        void finish(ConfigBinderProtocol.Response response, String error, boolean silent) {
            if (completed) {
                if (response != null) response.clear();
                return;
            }
            completed = true;
            main.removeCallbacks(timeout);
            if (bindingAttempted) {
                bindingAttempted = false;
                try { context.unbindService(this); }
                catch (RuntimeException ignored) { /* Failed binds may not have a live registration. */ }
            }
            // A synchronous Binder transaction cannot be cancelled. Keep the operation
            // slot until it returns, so a late old write cannot overwrite a newer save.
            if (!rpcInFlight) {
                request.clear();
                if (active == this) active = null;
            }
            Callback target = callback;
            callback = null;
            try {
                if (!silent && target != null) target.complete(response, error);
            } catch (RuntimeException ignored) {
                // A failed editor callback must not crash the hooked application.
            } finally {
                if (response != null) response.clear();
            }
        }
    }
}
