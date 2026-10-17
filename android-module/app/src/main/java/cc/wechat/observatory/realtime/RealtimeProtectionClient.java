package cc.wechat.observatory.realtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Process;

import cc.wechat.observatory.util.BridgeLogger;

/** An unexported receiver is a lifecycle token; it never receives configuration. */
public final class RealtimeProtectionClient {
    static final String ACTION = "cc.wechat.observatory.RUNTIME_PROTECTION_V1";
    static final String ACK_UID = "registered_uid";
    private static final Client CLIENT = new Client();

    private RealtimeProtectionClient() { }

    public static void update(Context context, boolean enabled) { CLIENT.update(context, enabled); }
    public static String statusText() { return CLIENT.status; }

    static final class Client {
        private final int platformApi;
        private Context owner;
        private BroadcastReceiver token;
        private boolean attempted;
        private volatile String status = "后台保护未启用";

        Client() { this(Build.VERSION.SDK_INT); }
        Client(int platformApi) { this.platformApi = platformApi; }

        String statusText() { return status; }

        synchronized void update(Context context, boolean enabled) {
            if (!enabled) {
                boolean hadToken = token != null;
                if (token != null) {
                    try { owner.unregisterReceiver(token); }
                    catch (IllegalArgumentException alreadyAbsent) { /* Registration never completed. */ }
                    catch (RuntimeException failed) {
                        status = "后台保护注销未确认，请重启微信";
                        return; // Retain the token so the next disable can retry.
                    }
                }
                owner = null;
                token = null;
                attempted = false;
                status = "后台保护未启用";
                if (hadToken) logResult("disabled");
                return;
            }
            if (attempted || context == null) return;
            if (platformApi != 36 || !ProtectionTokens.WECHAT.equals(context.getPackageName())) {
                status = "当前环境不支持后台保护";
                return;
            }
            attempted = true;
            Context application = context.getApplicationContext();
            owner = application == null ? context : application;
            token = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { }
            };
            try {
                Intent ack = owner.registerReceiver(token, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
                boolean confirmed = ack != null && ACTION.equals(ack.getAction())
                        && ack.getIntExtra(ACK_UID, -1) == Process.myUid();
                if (confirmed) {
                    status = "息屏保护已登记";
                } else {
                    status = "后台保护未确认：系统未适配，或系统框架作用域尚未加载";
                }
                logResult(confirmed ? "confirmed" : "unconfirmed");
            } catch (RuntimeException failed) {
                // Context may have registered before a reply failure. Keep the
                // receiver reference; disabling will attempt cleanup explicitly.
                status = "后台保护登记失败，请重启微信后重试";
                logResult("registration_failed");
            }
        }

        private static void logResult(String result) {
            BridgeLogger.log("realtime protection uid=" + Process.myUid() + " result=" + result);
        }
    }
}
