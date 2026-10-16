package cc.wechat.observatory.util;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

public final class BridgeLogger {
    private static final String TAG = "WechatGateway";

    private BridgeLogger() {
    }

    public static void log(String message) {
        // Keep both channels independent: framework log routing can hide a
        // module from logcat, and Xposed is unavailable in the settings app.
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // Logging must never break a hook or its recovery path.
        }
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
            // The Android log still records failures outside the hooked process.
        }
    }
}
