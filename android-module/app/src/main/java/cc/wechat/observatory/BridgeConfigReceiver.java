package cc.wechat.observatory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Properties;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BridgeConfigReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_CONFIG = "cc.wechat.observatory.SET_CONFIG";
    private static final String TAG = "WechatGateway";
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(BridgeConfigProvider.CONFIG_KEYS));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_SET_CONFIG.equals(intent.getAction())) {
            return;
        }
        Properties values = new Properties();
        int count = 0;
        for (String key : ALLOWED_KEYS) {
            if (!intent.hasExtra(key)) {
                continue;
            }
            String value = intent.getStringExtra(key);
            if (value == null || value.trim().isEmpty()) {
                values.remove(key);
            } else {
                values.setProperty(key, value.trim());
            }
            count++;
        }
        final int suppliedKeys = count;
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        try {
            new Thread(() -> {
                try {
                    ModuleConfigStore.SaveResult result = SharedConfigStore.save(application, values);
                    Log.i(TAG, result.success ? "stored shared bridge config keys=" + suppliedKeys
                            : "shared config save failed; grant the directory in module settings first");
                } finally { values.clear(); pending.finish(); }
            }, "Observatory shell configuration").start();
        } catch (RuntimeException unavailable) {
            values.clear();
            pending.finish();
            Log.i(TAG, "shared config worker unavailable");
        }
    }
}
