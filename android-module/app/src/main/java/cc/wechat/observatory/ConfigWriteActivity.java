package cc.wechat.observatory;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.view.WindowManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** A transient write operation in the module UID. No service or WeChat file is used. */
public final class ConfigWriteActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String nonce = null;
        String status = ConfigWriteContract.DENIED;
        Properties response = null;
        boolean loading = false;
        try {
            // Android supplies this identity only for a startActivityForResult caller.
            if (savedInstanceState != null || !ConfigWriteContract.allowedCaller(getCallingPackage())) return;
            Intent request = getIntent();
            nonce = request == null ? null : request.getStringExtra(ConfigWriteContract.EXTRA_NONCE);
            if (!ConfigWriteContract.validNonce(nonce)) {
                status = ConfigWriteContract.INVALID;
                return;
            }
            loading = ConfigWriteContract.ACTION_LOAD.equals(request.getAction());
            if (loading) {
                response = ModuleConfigStore.loadForRuntime(this);
                status = ConfigWriteContract.OK;
                return;
            }
            if (!ConfigWriteContract.ACTION_SAVE.equals(request.getAction())) {
                status = ConfigWriteContract.INVALID;
                return;
            }
            Bundle values = request.getBundleExtra(ConfigWriteContract.EXTRA_VALUES);
            if (values == null || values.size() > ConfigWriteContract.KEYS.size()) {
                status = ConfigWriteContract.INVALID;
                return;
            }
            Map<String, Object> received = new LinkedHashMap<>();
            for (String key : values.keySet()) received.put(key, values.get(key));
            Map<String, String> validated;
            try {
                validated = received.isEmpty() ? new LinkedHashMap<>() : ConfigWriteContract.normalize(received);
            } catch (Exception invalid) {
                status = ConfigWriteContract.INVALID;
                return;
            }
            Properties settings = new Properties();
            settings.putAll(validated);
            ModuleConfigStore.SaveResult saved = ModuleConfigStore.save(this, settings);
            settings.clear();
            validated.clear();
            received.clear();
            values.clear();
            if (!saved.success) {
                status = ConfigWriteContract.FAILED;
                return;
            }
            loading = request.getBooleanExtra(ConfigWriteContract.EXTRA_NEEDS_SESSION, false);
            response = loading ? ModuleConfigStore.loadForRuntime(this) : ModuleConfigStore.read(this);
            status = saved.revision != null && saved.revision.equals(response.getProperty(ConfigWriteContract.REVISION_KEY))
                    ? ConfigWriteContract.OK : ConfigWriteContract.FAILED;
        } catch (Throwable error) {
            // Never log request values or exception messages that might contain them.
            status = ConfigWriteContract.FAILED;
        } finally {
            Intent result = new Intent();
            if (ConfigWriteContract.validNonce(nonce)) result.putExtra(ConfigWriteContract.EXTRA_NONCE, nonce);
            result.putExtra(ConfigWriteContract.EXTRA_STATUS, status);
            if (response != null && ConfigWriteContract.OK.equals(status)) {
                Bundle strings = new Bundle();
                for (Map.Entry<String, String> entry : ConfigWriteContract.resultSnapshot(response, loading).entrySet()) {
                    strings.putString(entry.getKey(), entry.getValue());
                }
                result.putExtra(ConfigWriteContract.EXTRA_VALUES, strings);
                response.clear();
                Context app = getApplicationContext();
                try {
                    new Thread(() -> {
                        try { LegacyConfigCleanup.cleanup(app, false); }
                        catch (Throwable ignored) { }
                    }, "Observatory legacy cleanup").start();
                } catch (Throwable ignored) { }
            }
            setResult(ConfigWriteContract.OK.equals(status) ? RESULT_OK : RESULT_CANCELED, result);
            // Remove secrets from the Activity's Intent before leaving the task.
            setIntent(new Intent());
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // This operation is never recreated from an Android saved-state bundle.
    }
}
