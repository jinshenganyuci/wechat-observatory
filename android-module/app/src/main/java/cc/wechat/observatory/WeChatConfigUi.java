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

/** In-memory editor; the client persists configuration inside WeChat. */
public final class WeChatConfigUi {
    private static final String STORAGE_NOTICE = "配置保存在微信内，无需保持 Observatory 后台。"
            + "\n卸载模块前，请先点击“停用并删除配置”。";
    private static final String DELETE_UNCONFIRMED = "网关已停用，配置文件删除未确认，请重试。";
    private static final String MAIN_SETTINGS =
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI";
    // Values must not retain either the weak Activity key or its dialog/views.
    private static final Map<Activity, WeakReference<Session>> EDITORS = new WeakHashMap<>();
    private static volatile boolean removed;

    private WeChatConfigUi() { }

    static void moduleRemoved() {
        removed = true;
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
        if (removed || activity.isFinishing() || activity.isDestroyed()) return;
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
        }, true, new LinkedHashMap<>(), true);
    }

    private static void showLoaded(Activity activity) {
        Map<String, String> snapshot = ConfigWriteContract.editorSnapshot(
                BridgeConfig.readSettingsForEditor(activity));
        Session session = new Session(activity, snapshot, HookEntry.runtimeStatusText());
        synchronized (EDITORS) { EDITORS.put(activity, new WeakReference<>(session)); }
        session.show();
    }

    public static void load(Activity activity, Runnable onLoaded) {
        if (!removed && !activity.isFinishing() && !activity.isDestroyed()) {
            launch(activity, null, onLoaded, true, new LinkedHashMap<>(), false);
        }
    }

    private static void confirmDelete(Session session) {
        Activity activity = session.owner.get();
        if (activity != null) confirmDelete(activity, session);
    }

    private static void confirmDelete(Activity activity, Session session) {
        if (removed || activity.isFinishing() || activity.isDestroyed()
                || session != null && !session.isOpen()) return;
        WeakReference<Activity> owner = new WeakReference<>(activity);
        WeakReference<Session> editor = new WeakReference<>(session);
        boolean hasEditor = session != null;
        new AlertDialog.Builder(dialogContext(activity))
                .setTitle("停用并删除配置")
                .setMessage("停用网关，并删除保存在微信内的 Observatory 专用配置？下次使用需要重新填写服务地址和 API Key。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停用并删除", (dialog, which) -> {
                    Activity target = owner.get();
                    Session current = editor.get();
                    if (removed || target == null || target.isFinishing() || target.isDestroyed()
                            || hasEditor && (current == null || !current.isOpen())) return;
                    launch(target, current, null, false, new LinkedHashMap<>(), true);
                }).show();
    }

    private static void save(Session session) {
        Activity owner = session.owner.get();
        if (owner == null || !session.isOpen()) return;
        if (ConfigFileClient.get(owner).isBusy()) {
            session.finishSave("配置操作尚未完成，请稍后重试。");
            return;
        }
        Map<String, String> values = session.valuesForSave();
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
        } finally { values.clear(); }
        launch(owner, session, null, false, validated, true);
    }

    private static void launch(Activity owner, Session session, Runnable onLoaded, boolean loading,
                               Map<String, String> validated, boolean interactive) {
        if (removed) { validated.clear(); return; }
        ConfigFileClient client = ConfigFileClient.get(owner);
        if (client.isBusy()) {
            validated.clear();
            if (interactive) Toast.makeText(owner, "配置操作尚未完成，请稍后重试。", Toast.LENGTH_SHORT).show();
            return;
        }
        WeakReference<Activity> activity = new WeakReference<>(owner);
        WeakReference<Session> editor = new WeakReference<>(session);
        boolean needsSession = !BridgeConfig.hasRuntimeSession();
        boolean deleting = !loading && validated.isEmpty();
        if (session != null) {
            session.setSaving(true);
            session.status.setText(deleting ? "正在停用并删除配置…" : "正在保存配置…");
        }
        try {
            if (deleting) {
                // Stop before touching disk: a timed-out deletion may already
                // have committed, so its old runtime must never keep sending.
                HookEntry.acceptConfiguration(new Properties());
                HookEntry.configurationChanged();
                if (session != null) {
                    session.enabled.setChecked(false);
                    session.backgroundRealtime.setChecked(false);
                }
            }
            boolean accepted = client.execute(loading, needsSession, validated, (response, error) -> {
                if (removed) return;
                boolean success = false;
                String message = error;
                if (message == null && response != null && (!deleting || response.isEmpty())) {
                    Properties settings = new Properties();
                    try {
                        settings.putAll(response);
                        boolean changed = settings.isEmpty() || needsSession || !ConfigWriteContract.editorSnapshot(
                                BridgeConfig.readSettingsForEditor(null)).equals(
                                ConfigWriteContract.editorSnapshot(settings));
                        HookEntry.acceptConfiguration(settings);
                        if (changed) HookEntry.configurationChanged();
                        success = true;
                        message = settings.isEmpty() ? (loading ? "尚未配置，请填写服务地址和 API Key。" : "已停用并删除配置。")
                                : (loading ? "配置已加载" : "配置已保存，在微信内立即生效。");
                    } catch (RuntimeException invalid) {
                        message = "无法加载网关配置，请重新打开网关设置。";
                    } finally { settings.clear(); }
                }
                if (deleting && !success) message = DELETE_UNCONFIRMED;
                else if (message == null) message = "配置读取结果无效，请重新打开网关设置。";
                Session current = editor.get();
                Activity target = activity.get();
                if (current != null && current.isOpen()) {
                    current.finishSave(message);
                    if (success && !loading) {
                        if (deleting) current.clearSnapshot();
                        else current.refreshSnapshot();
                    }
                } else if (interactive && (!success || !loading) && target != null
                        && !target.isFinishing() && !target.isDestroyed()) {
                    if (!success && loading) showReadFailure(target, message);
                    else Toast.makeText(target, deleting && success
                            ? "已停用并删除配置，请重新打开网关设置。" : message, Toast.LENGTH_LONG).show();
                }
                if (success && loading && onLoaded != null) {
                    try { onLoaded.run(); }
                    catch (RuntimeException failure) {
                        BridgeLogger.log("config loaded callback failed: " + failure.getClass().getSimpleName());
                    }
                }
            });
            if (!accepted && session != null && session.isOpen()) {
                session.finishSave(deleting ? DELETE_UNCONFIRMED : "配置操作尚未完成，请稍后重试。");
            }
        } catch (RuntimeException unavailable) {
            if (session != null) session.finishSave(deleting ? DELETE_UNCONFIRMED : "配置暂时无法读写，请重新打开网关设置。");
            else if (interactive && deleting) Toast.makeText(owner, DELETE_UNCONFIRMED, Toast.LENGTH_LONG).show();
            else if (interactive) showReadFailure(owner, "配置暂时无法读取，请稍后重试。");
        } finally { validated.clear(); }
    }

    private static void showReadFailure(Activity activity, String message) {
        if (removed || activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(dialogContext(activity))
                .setTitle("无法读取网关配置")
                .setMessage(message)
                .setNegativeButton("关闭", null)
                .setPositiveButton("重新读取", (dialog, which) -> show(activity))
                .setNeutralButton("停用并删除配置", (dialog, which) -> confirmDelete(activity, null))
                .show();
    }

    private static final class Session {
        final WeakReference<Activity> owner;
        final Map<String, String> snapshot;
        final AlertDialog dialog;
        final EditText url;
        final EditText key;
        final Switch enabled;
        final Switch websocket;
        final Switch backgroundRealtime;
        final TextView status;

        Session(Activity activity, Map<String, String> snapshot, String runtimeStatus) {
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
                    ConfigWriteContract.isOn(snapshot.get("enabled"), false));
            websocket = toggle(ui, fields, "实时 WebSocket",
                    ConfigWriteContract.isOn(snapshot.get("outbox_websocket_enabled"), true));
            backgroundRealtime = toggle(ui, fields, "Oplus 息屏保护",
                    "1".equals(snapshot.get("background_realtime_enabled")));
            TextView protectionNotice = new TextView(ui);
            protectionNotice.setText("仅适用于已适配的一加 / OPPO 系统；需启用 LSPosed 系统框架作用域并重启，可能增加后台耗电。");
            protectionNotice.setTextSize(13);
            protectionNotice.setAlpha(0.75f);
            protectionNotice.setPadding(0, 0, 0, dp(ui, 8));
            protectionNotice.setSaveEnabled(false);
            fields.addView(protectionNotice);
            status = new TextView(ui);
            status.setTextSize(13);
            status.setAlpha(0.75f);
            status.setPadding(0, dp(ui, 12), 0, dp(ui, 4));
            status.setText(runtimeStatus
                    + (snapshot.isEmpty() ? "\n请填写配置；从 0.1.17 升级也需重新填写。" : "")
                    + "\n" + STORAGE_NOTICE);
            status.setSaveEnabled(false);
            fields.addView(status);
            ScrollView scroll = new ScrollView(ui);
            scroll.setSaveEnabled(false);
            scroll.setSaveFromParentEnabled(false);
            scroll.addView(fields);
            dialog = new AlertDialog.Builder(ui).setTitle("Observatory 网关")
                    .setView(scroll).setPositiveButton("保存", null).setNegativeButton("关闭", null)
                    .setNeutralButton("停用并删除配置", null).create();
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

        void show() {
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.show();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save(this));
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> confirmDelete(this));
        }

        boolean isOpen() {
            Activity activity = owner.get();
            return activity != null && !activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing();
        }

        Map<String, String> valuesForSave() {
            Map<String, String> values = new LinkedHashMap<>(snapshot);
            values.put("bridge_url", url.getText().toString());
            values.put("api_key", key.getText().toString());
            values.put("enabled", enabled.isChecked() ? "1" : "0");
            values.put("outbox_websocket_enabled", websocket.isChecked() ? "1" : "0");
            values.put("background_realtime_enabled", backgroundRealtime.isChecked() ? "1" : "0");
            return values;
        }

        void refreshSnapshot() {
            snapshot.clear();
            snapshot.putAll(ConfigWriteContract.editorSnapshot(BridgeConfig.readSettingsForEditor(null)));
            url.setText(snapshot.get("bridge_url"));
            key.setText(snapshot.get("api_key"));
            enabled.setChecked(ConfigWriteContract.isOn(snapshot.get("enabled"), false));
            websocket.setChecked(ConfigWriteContract.isOn(snapshot.get("outbox_websocket_enabled"), true));
            backgroundRealtime.setChecked("1".equals(snapshot.get("background_realtime_enabled")));
        }

        void clearSnapshot() {
            snapshot.clear();
            url.setText("");
            key.setText("");
            enabled.setChecked(false);
            websocket.setChecked(true);
            backgroundRealtime.setChecked(false);
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
            backgroundRealtime.setEnabled(!saving);
        }

        void finishSave(String message) {
            setSaving(false);
            status.setText(message + "\n" + HookEntry.runtimeStatusText() + "\n" + STORAGE_NOTICE);
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
