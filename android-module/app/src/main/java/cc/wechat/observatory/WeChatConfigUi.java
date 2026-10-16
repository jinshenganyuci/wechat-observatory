package cc.wechat.observatory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.WeakHashMap;

import cc.wechat.observatory.config.BridgeConfig;
import cc.wechat.observatory.util.BridgeLogger;
import cc.wechat.observatory.util.HookInstallation;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** In-memory editor; all durable writes happen in ConfigWriteActivity's module UID. */
public final class WeChatConfigUi {
    private static final String MAIN_SETTINGS =
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI";
    private static final HookInstallation RESULT_HOOKS = new HookInstallation();
    // Values must not retain either the weak Activity key or its dialog/views.
    private static final Map<Activity, WeakReference<Session>> EDITORS = new WeakHashMap<>();
    private static final Map<Activity, Pending> PENDING = new WeakHashMap<>();
    private static final long SAVE_TIMEOUT_MS = 15000L;

    private WeChatConfigUi() { }

    public static void show(Activity activity) throws Throwable {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (PENDING) {
            if (PENDING.containsKey(activity)) {
                Toast.makeText(activity, "配置组件正在处理，请稍后重新打开。", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!MAIN_SETTINGS.equals(activity.getClass().getName())) {
            throw new IllegalArgumentException("unsupported settings page");
        }
        installResultHook(activity.getClass());
        synchronized (EDITORS) {
            WeakReference<Session> existing = EDITORS.get(activity);
            Session session = existing == null ? null : existing.get();
            if (session != null && session.dialog.isShowing()) return;
        }
        Map<String, String> snapshot = ConfigWriteContract.editorSnapshot(
                BridgeConfig.readSettingsForEditor(activity));
        Session session = new Session(activity, snapshot);
        synchronized (EDITORS) {
            EDITORS.put(activity, new WeakReference<>(session));
        }
        if (session.dialog.getWindow() != null) {
            session.dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        session.dialog.show();
        session.dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save(session));
    }

    public static void load(Activity activity, Runnable onLoaded) throws Throwable {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        installResultHook(activity.getClass());
        launch(activity, null, onLoaded, true, new LinkedHashMap<>());
    }

    private static void installResultHook(Class<?> page) throws Throwable {
        Method result = resultMethod(page);
        // For the audited settings page this resolves to MMActivity; VAS
        // fragment result forwarding also reaches that method. Other foreground
        // pages may declare their own override, so bind the nearest declaration.
        RESULT_HOOKS.installOnce(result, () -> XposedBridge.hookMethod(result, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject == null || !(param.thisObject instanceof Activity)
                        || !Integer.valueOf(ConfigWriteContract.REQUEST_CODE).equals(param.args[0])) return;
                Activity owner = (Activity) param.thisObject;
                Pending pending;
                synchronized (PENDING) { pending = PENDING.get(owner); }
                if (pending == null) return;
                Intent data = (Intent) param.args[2];
                String nonce;
                try {
                    nonce = data == null ? null : data.getStringExtra(ConfigWriteContract.EXTRA_NONCE);
                } catch (RuntimeException invalid) { return; }
                // Preserve every unrelated result, including request-code collisions.
                if (!pending.nonce.equals(nonce)) return;
                synchronized (PENDING) {
                    if (PENDING.get(owner) != pending) return;
                    PENDING.remove(owner);
                }
                param.setResult(null);
                try {
                    String status = data.getStringExtra(ConfigWriteContract.EXTRA_STATUS);
                    if (((Integer) param.args[1]) != Activity.RESULT_OK || !ConfigWriteContract.OK.equals(status)) {
                        complete(owner, pending, errorMessage(status), false);
                        return;
                    }
                    Bundle values = data.getBundleExtra(ConfigWriteContract.EXTRA_VALUES);
                    if (values == null || values.size() > ConfigWriteContract.KEYS.size() + ConfigWriteContract.RUNTIME_KEYS.size()) {
                        throw new IllegalArgumentException("invalid result size");
                    }
                    Properties settings = new Properties();
                    for (String key : values.keySet()) {
                        if (!ConfigWriteContract.KEYS.contains(key) && !ConfigWriteContract.RUNTIME_KEYS.contains(key)) {
                            throw new IllegalArgumentException("invalid result key");
                        }
                        Object value = values.get(key);
                        if (!(value instanceof String) || ((String) value).length() > 8192) {
                            throw new IllegalArgumentException("invalid result value");
                        }
                        if (!pending.needsSession && ConfigWriteContract.RUNTIME_KEYS.contains(key)
                                && !ConfigWriteContract.REVISION_KEY.equals(key)) {
                            throw new IllegalArgumentException("save changed runtime identity");
                        }
                        settings.setProperty(key, (String) value);
                    }
                    BridgeConfig.acceptRuntimeSettings(settings);
                    settings.clear();
                    values.clear();
                    data.removeExtra(ConfigWriteContract.EXTRA_VALUES);
                    HookEntry.configurationChanged();
                    complete(owner, pending, pending.loading ? "配置已加载" : "配置已保存，微信内存配置已更新。", true);
                } catch (Throwable error) {
                    complete(owner, pending, "无法接收模块配置，请重新打开网关配置后重试。", false);
                }
            }
        }));
    }

    private static Method resultMethod(Class<?> page) throws NoSuchMethodException {
        for (Class<?> type = page; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
                if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException("invalid activity result signature");
                }
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("settings onActivityResult");
    }

    private static void save(Session session) {
        Activity owner = session.owner.get();
        if (owner == null || !session.isOpen()) return;
        synchronized (PENDING) {
            if (PENDING.containsKey(owner)) return;
        }
        Map<String, String> values = session.snapshot;
        values.put("bridge_url", session.url.getText().toString());
        values.put("api_key", session.key.getText().toString());
        values.put("enabled", session.enabled.isChecked() ? "1" : "0");
        values.put("outbox_websocket_enabled", session.websocket.isChecked() ? "1" : "0");
        Map<String, String> validated;
        try {
            validated = ConfigWriteContract.normalize(values);
        } catch (MalformedURLException invalidUrl) {
            session.url.setError("请填写有效的 http:// 或 https:// 服务地址，不含用户名、查询参数或片段。");
            session.url.requestFocus();
            return;
        } catch (IllegalArgumentException invalid) {
            session.finishSave("配置内容无效或过长，请检查后重试。");
            return;
        }
        launch(owner, session, null, false, validated);
    }

    private static void launch(Activity owner, Session session, Runnable onLoaded, boolean loading,
                               Map<String, String> validated) {
        synchronized (PENDING) { if (PENDING.containsKey(owner)) return; }
        String nonce = UUID.randomUUID().toString();
        Bundle strings = new Bundle();
        for (Map.Entry<String, String> entry : validated.entrySet()) strings.putString(entry.getKey(), entry.getValue());
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(ConfigWriteContract.MODULE_PACKAGE, ConfigWriteContract.WRITE_ACTIVITY));
        intent.setAction(loading ? ConfigWriteContract.ACTION_LOAD : ConfigWriteContract.ACTION_SAVE);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(ConfigWriteContract.EXTRA_NONCE, nonce);
        intent.putExtra(ConfigWriteContract.EXTRA_VALUES, strings);
        boolean needsSession = loading || !BridgeConfig.hasRuntimeSession();
        intent.putExtra(ConfigWriteContract.EXTRA_NEEDS_SESSION, needsSession);
        Pending pending = new Pending(nonce, session, loading, needsSession, onLoaded);
        synchronized (PENDING) { PENDING.put(owner, pending); }
        if (session != null) {
            session.setSaving(true);
            session.status.setText("正在打开模块保存组件…");
        }
        try {
            owner.startActivityForResult(intent, ConfigWriteContract.REQUEST_CODE, null);
        } catch (Throwable unavailable) {
            synchronized (PENDING) {
                if (PENDING.get(owner) == pending) PENDING.remove(owner);
            }
            complete(owner, pending, "无法启动模块配置组件：模块可能被卸载、禁用或被应用隐藏规则阻止。配置未加载或保存。", false);
            BridgeLogger.log("config writer start failed: " + unavailable.getClass().getSimpleName());
            return;
        } finally {
            // Binder has copied the payload; retain no second credentials snapshot.
            intent.removeExtra(ConfigWriteContract.EXTRA_VALUES);
            strings.clear();
            validated.clear();
        }
        WeakReference<Activity> reference = new WeakReference<>(owner);
        // VASActivity can swallow a Fragment launch exception. A missing result
        // must not leave the UI claiming success or disabled forever.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Activity target = reference.get();
            if (target == null) return;
            Pending waiting;
            synchronized (PENDING) {
                waiting = PENDING.get(target);
                if (waiting == null || !waiting.nonce.equals(nonce)) return;
                PENDING.remove(target);
            }
            complete(target, waiting, "配置组件没有返回结果。请检查模块是否启用或被隐藏规则阻止；不能确认配置已加载或保存。", false);
        }, SAVE_TIMEOUT_MS);
    }

    private static void complete(Activity owner, Pending pending, String message, boolean success) {
        Session session = pending.session.get();
        if (session != null && session.isOpen()) session.finishSave(message);
        else if ((!success || !pending.loading) && !owner.isFinishing() && !owner.isDestroyed()) {
            Toast.makeText(owner, message, Toast.LENGTH_LONG).show();
        }
        if (success && pending.loading) {
            Runnable callback = pending.onLoaded.get();
            if (callback != null) {
                try { callback.run(); }
                catch (Throwable error) {
                    BridgeLogger.log("config loaded callback failed: " + error.getClass().getSimpleName());
                }
            }
        }
    }

    private static String errorMessage(String status) {
        if (ConfigWriteContract.INVALID.equals(status)) return "保存组件拒绝了无效配置，请检查服务地址后重试。";
        if (ConfigWriteContract.DENIED.equals(status)) return "保存组件无法验证调用来源，配置未保存。";
        return "模块配置读取或保存失败，请检查存储空间并重试。";
    }

    private static final class Pending {
        final String nonce;
        final WeakReference<Session> session;
        final WeakReference<Runnable> onLoaded;
        final boolean loading;
        final boolean needsSession;
        Pending(String nonce, Session session, boolean loading, boolean needsSession, Runnable onLoaded) {
            this.nonce = nonce;
            this.session = new WeakReference<>(session);
            this.onLoaded = new WeakReference<>(onLoaded);
            this.loading = loading;
            this.needsSession = needsSession;
        }
    }

    private static final class Session {
        final WeakReference<Activity> owner;
        final Map<String, String> snapshot;
        final AlertDialog dialog;
        final EditText url;
        final EditText key;
        final CheckBox enabled;
        final CheckBox websocket;
        final TextView status;

        Session(Activity activity, Map<String, String> snapshot) {
            this.owner = new WeakReference<>(activity);
            this.snapshot = snapshot;
            LinearLayout fields = new LinearLayout(activity);
            fields.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
            fields.setPadding(padding, padding / 2, padding, padding);
            fields.setSaveEnabled(false);
            fields.setSaveFromParentEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) fields.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            url = input(activity, fields, "服务地址", snapshot.get("bridge_url"), false);
            key = input(activity, fields, "API Key", snapshot.get("api_key"), true);
            enabled = new CheckBox(activity);
            enabled.setText("启用网关");
            enabled.setChecked(ConfigWriteContract.isOn(snapshot.get("enabled"), true));
            enabled.setSaveEnabled(false);
            fields.addView(enabled);
            websocket = new CheckBox(activity);
            websocket.setText("实时 WebSocket");
            websocket.setChecked(ConfigWriteContract.isOn(snapshot.get("outbox_websocket_enabled"), true));
            websocket.setSaveEnabled(false);
            fields.addView(websocket);
            status = new TextView(activity);
            status.setText(HookEntry.runtimeStatusText()
                    + "\n微信每次进程启动后首次进入前台，会短暂打开模块读取配置；无需让模块一直在后台运行。保存后返回微信，高级配置沿用现有值。");
            status.setSaveEnabled(false);
            fields.addView(status);
            ScrollView scroll = new ScrollView(activity);
            scroll.setSaveEnabled(false);
            scroll.setSaveFromParentEnabled(false);
            scroll.addView(fields);
            dialog = new AlertDialog.Builder(activity).setTitle("Observatory 网关")
                    .setView(scroll).setPositiveButton("保存", null).setNegativeButton("关闭", null)
                    .setNeutralButton("清除配置", (ignored, which) -> new AlertDialog.Builder(activity)
                            .setMessage("清除模块保存的网关地址和 API Key？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清除", (confirmation, button) ->
                                    launch(activity, null, null, false, new LinkedHashMap<>()))
                            .show()).create();
            dialog.setOnDismissListener(ignored -> {
                Activity target = owner.get();
                if (target != null) {
                    synchronized (EDITORS) {
                        WeakReference<Session> ref = EDITORS.get(target);
                        if (ref != null && ref.get() == this) EDITORS.remove(target);
                    }
                    // An already-started save still updates runtime memory after
                    // this dialog closes; the pending entry only weakly owns us.
                }
                url.setText("");
                key.setText("");
                snapshot.clear();
            });
        }

        boolean isOpen() {
            Activity activity = owner.get();
            return activity != null && !activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing();
        }

        void setSaving(boolean saving) {
            Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) button.setEnabled(!saving);
            url.setEnabled(!saving);
            key.setEnabled(!saving);
            enabled.setEnabled(!saving);
            websocket.setEnabled(!saving);
        }

        void finishSave(String message) {
            setSaving(false);
            status.setText(message + "\n" + HookEntry.runtimeStatusText());
        }
    }

    private static EditText input(Activity activity, LinearLayout parent, String label, String value, boolean password) {
        TextView title = new TextView(activity);
        title.setText(label);
        parent.addView(title);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setSaveEnabled(false);
        field.setSaveFromParentEnabled(false);
        field.setFreezesText(false);
        if (Build.VERSION.SDK_INT >= 26) field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setText(value == null ? "" : value);
        parent.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }
}
