package cc.wechat.observatory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.WeakHashMap;

import cc.wechat.observatory.config.BridgeConfig;
import cc.wechat.observatory.util.BridgeLogger;

/** In-memory editor; ConfigService owns all durable reads and writes in the module UID. */
public final class WeChatConfigUi {
    private static final String MAIN_SETTINGS =
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI";
    // Values must not retain either the weak Activity key or its dialog/views.
    private static final Map<Activity, WeakReference<Session>> EDITORS = new WeakHashMap<>();

    private WeChatConfigUi() { }

    static void moduleRemoved() {
        ArrayList<WeakReference<Session>> editors;
        synchronized (EDITORS) {
            editors = new ArrayList<>(EDITORS.values());
            EDITORS.clear();
        }
        for (WeakReference<Session> reference : editors) {
            Session session = reference.get();
            if (session != null) {
                try { session.dialog.dismiss(); }
                catch (RuntimeException ignored) { }
                finally {
                    session.url.setText("");
                    session.key.setText("");
                    session.snapshot.clear();
                }
            }
        }
    }

    public static void show(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!MAIN_SETTINGS.equals(activity.getClass().getName())) {
            throw new IllegalArgumentException("unsupported settings page");
        }
        synchronized (EDITORS) {
            WeakReference<Session> existing = EDITORS.get(activity);
            Session session = existing == null ? null : existing.get();
            if (session != null && session.dialog.isShowing()) return;
        }
        // Re-read on opening: a previous timed-out write may have committed even
        // though its result was lost. Never edit and overwrite an old snapshot.
        WeakReference<Activity> owner = new WeakReference<>(activity);
        launch(activity, null, () -> {
            Activity target = owner.get();
            if (target != null && !target.isFinishing() && !target.isDestroyed()) showLoaded(target);
        }, true, new LinkedHashMap<>());
    }

    private static void showLoaded(Activity activity) {
        Map<String, String> snapshot = ConfigWriteContract.editorSnapshot(
                BridgeConfig.readSettingsForEditor(activity));
        Session session = new Session(activity, snapshot);
        synchronized (EDITORS) { EDITORS.put(activity, new WeakReference<>(session)); }
        if (session.dialog.getWindow() != null) {
            session.dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        session.dialog.show();
        session.dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save(session));
    }

    public static void load(Activity activity, Runnable onLoaded) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            launch(activity, null, onLoaded, true, new LinkedHashMap<>());
        }
    }

    private static void save(Session session) {
        Activity owner = session.owner.get();
        if (owner == null || !session.isOpen()) return;
        if (ConfigServiceClient.get(owner).isBusy()) {
            session.finishSave("配置操作尚未完成，请稍后重试。");
            return;
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
        ConfigServiceClient client = ConfigServiceClient.get(owner);
        if (client.isBusy()) {
            validated.clear();
            Toast.makeText(owner, "配置操作尚未完成，请稍后重试。", Toast.LENGTH_SHORT).show();
            return;
        }
        WeakReference<Activity> activity = new WeakReference<>(owner);
        WeakReference<Session> editor = new WeakReference<>(session);
        boolean needsSession = !BridgeConfig.hasRuntimeSession();
        if (session != null) {
            session.setSaving(true);
            session.status.setText("正在保存配置…");
        }
        try {
            client.execute(loading, needsSession, validated, (response, error) -> {
                boolean success = false;
                String message = error;
                if (message == null && response != null) {
                    if (!ConfigWriteContract.OK.equals(response.status)) message = errorMessage(response.status);
                    else {
                        Properties settings = new Properties();
                        try {
                            settings.putAll(response.values);
                            boolean changed = needsSession || !ConfigWriteContract.editorSnapshot(
                                    BridgeConfig.readSettingsForEditor(null)).equals(
                                    ConfigWriteContract.editorSnapshot(settings));
                            BridgeConfig.acceptRuntimeSettings(settings);
                            if (changed) HookEntry.configurationChanged();
                            success = true;
                            message = loading ? "配置已加载" : "配置已保存，立即生效。";
                        } catch (RuntimeException invalid) {
                            message = "无法接收模块配置，请重新打开网关设置。";
                        } finally { settings.clear(); }
                    }
                }
                if (message == null) message = "配置服务未返回有效结果，请重试。";
                Session current = editor.get();
                Activity target = activity.get();
                if (current != null && current.isOpen()) {
                    current.finishSave(message);
                    if (success && !loading) current.refreshSnapshot();
                } else if ((!success || !loading) && target != null
                        && !target.isFinishing() && !target.isDestroyed()) {
                    Toast.makeText(target, message, Toast.LENGTH_LONG).show();
                }
                if (success && loading && onLoaded != null) {
                    try { onLoaded.run(); }
                    catch (RuntimeException failure) {
                        BridgeLogger.log("config loaded callback failed: " + failure.getClass().getSimpleName());
                    }
                }
            });
        } catch (RuntimeException unavailable) {
            if (session != null) session.finishSave("配置服务暂时不可用，请重新打开网关设置。");
            else Toast.makeText(owner, "配置服务暂时不可用，请稍后重试。", Toast.LENGTH_LONG).show();
        } finally { validated.clear(); }
    }

    private static String errorMessage(String status) {
        if (ConfigWriteContract.INVALID.equals(status)) return "配置内容无效，请检查后重试。";
        if (ConfigWriteContract.DENIED.equals(status)) return "配置服务无法验证微信身份，未读取或保存配置。";
        if (ConfigBinderProtocol.EXPIRED.equals(status)) return "配置操作已超时，请重新打开网关设置。";
        return "配置读写失败，请检查存储空间后重新读取。";
    }

    private static final class Session {
        final WeakReference<Activity> owner;
        final Map<String, String> snapshot;
        final AlertDialog dialog;
        final EditText url;
        final EditText key;
        final Switch enabled;
        final Switch websocket;
        final TextView status;

        Session(Activity activity, Map<String, String> snapshot) {
            this.owner = new WeakReference<>(activity);
            this.snapshot = snapshot;
            Context ui = dialogContext(activity);
            LinearLayout fields = new LinearLayout(ui);
            fields.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
            fields.setPadding(padding, padding / 2, padding, padding);
            fields.setSaveEnabled(false);
            fields.setSaveFromParentEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) fields.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            url = input(ui, fields, "服务地址", snapshot.get("bridge_url"), false);
            key = input(ui, fields, "API Key", snapshot.get("api_key"), true);
            enabled = toggle(ui, fields, "启用网关",
                    ConfigWriteContract.isOn(snapshot.get("enabled"), true));
            websocket = toggle(ui, fields, "实时 WebSocket",
                    ConfigWriteContract.isOn(snapshot.get("outbox_websocket_enabled"), true));
            status = new TextView(ui);
            status.setTextSize(13);
            status.setAlpha(0.75f);
            status.setPadding(0, dp(ui, 12), 0, dp(ui, 4));
            status.setText(HookEntry.runtimeStatusText()
                    + "\n保存后生效，无需保持 Observatory 后台。");
            status.setSaveEnabled(false);
            fields.addView(status);
            ScrollView scroll = new ScrollView(ui);
            scroll.setSaveEnabled(false);
            scroll.setSaveFromParentEnabled(false);
            scroll.addView(fields);
            dialog = new AlertDialog.Builder(ui).setTitle("Observatory 网关")
                    .setView(scroll).setPositiveButton("保存", null).setNegativeButton("关闭", null)
                    .setNeutralButton("清除配置", (ignored, which) -> new AlertDialog.Builder(ui)
                            .setMessage("清除模块保存的网关地址和 API Key？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清除", (confirmation, button) ->
                                    launch(activity, this, null, false, new LinkedHashMap<>()))
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

        void refreshSnapshot() {
            snapshot.clear();
            snapshot.putAll(ConfigWriteContract.editorSnapshot(BridgeConfig.readSettingsForEditor(null)));
            url.setText(snapshot.get("bridge_url"));
            key.setText(snapshot.get("api_key"));
            enabled.setChecked(ConfigWriteContract.isOn(snapshot.get("enabled"), true));
            websocket.setChecked(ConfigWriteContract.isOn(snapshot.get("outbox_websocket_enabled"), true));
        }

        void setSaving(boolean saving) {
            Button clear = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (clear != null) clear.setEnabled(!saving);
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

    private static Context dialogContext(Activity activity) {
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // A resource-id ContextThemeWrapper overlays the host theme. Start with
        // an empty Theme instead, so WeChat's compound-button backgrounds and
        // other widget styles cannot leak into the settings dialog.
        Resources.Theme theme = activity.getResources().newTheme();
        theme.applyStyle(dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert, true);
        return new ContextThemeWrapper(activity, theme);
    }

    private static Switch toggle(Context ui, LinearLayout parent, String label, boolean checked) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ui, 56));
        row.setSaveEnabled(false);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        TextView title = new TextView(ui);
        title.setText(label);
        title.setTextSize(16);
        title.setPadding(0, dp(ui, 8), dp(ui, 16), dp(ui, 8));
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch control = new Switch(ui);
        control.setShowText(false);
        control.setContentDescription(label);
        control.setChecked(checked);
        control.setSaveEnabled(false);
        control.setSaveFromParentEnabled(false);
        control.setBackground(null);
        control.setBackgroundTintList(null);
        control.setPadding(0, 0, 0, 0);
        control.setMinimumWidth(0);
        control.setMinWidth(0);
        control.setMinimumHeight(dp(ui, 48));
        control.setSwitchMinWidth(dp(ui, 52));
        // Only the text column expands. The native thumb and track keep their
        // system dimensions, with a full-height touch target and checked state.
        row.addView(control, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(view -> {
            if (control.isEnabled()) control.performClick();
        });
        row.setFocusable(false);
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return control;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static EditText input(Context ui, LinearLayout parent, String label, String value, boolean password) {
        TextView title = new TextView(ui);
        title.setText(label);
        parent.addView(title);
        EditText field = new EditText(ui);
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
