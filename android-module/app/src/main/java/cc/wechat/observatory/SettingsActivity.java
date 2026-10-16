package cc.wechat.observatory;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.util.Properties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.text.DateFormat;
import java.util.Date;
import org.json.JSONObject;
import cc.wechat.observatory.gateway.ConnectionProbe;
import java.util.Map;
import java.lang.ref.WeakReference;

import cc.wechat.observatory.config.BridgeConfig;
import cc.wechat.observatory.gateway.GatewayEndpoint;

public final class SettingsActivity extends Activity {
    private static final int DIRECTORY_PERMISSION_REQUEST = 0x4f31;
    private static final String SHARED_CONFIG_LOCATION = "Documents/WeChatObservatory/observatory.properties";
    private static final String DEFAULT_POLL_INTERVAL_MS = "1000";
    private static final String DEFAULT_POLL_LIMIT = "1";
    private static final String DEFAULT_OUTBOX_WEBSOCKET_ENABLED = "1";
    private static final String DEFAULT_CONTACT_SYNC_INTERVAL_MS = "600000";
    private static final String DEFAULT_CONTACT_SYNC_LIMIT = String.valueOf(BridgeConfig.DEFAULT_CONTACT_SYNC_LIMIT);
    private static final String DEFAULT_CONTACT_INCLUDE_CHATROOMS = "1";
    private static final String DEFAULT_MEDIA_UPLOAD_ENABLED = "0";
    private static final String DEFAULT_MEDIA_UPLOAD_LIMIT_BYTES = "5242880";
    private static final String DEFAULT_STALE_MESSAGE_GRACE_MS = String.valueOf(BridgeConfig.DEFAULT_STALE_MESSAGE_GRACE_MS);
    private static final String DEFAULT_STALE_MESSAGE_REPLAY_LIMIT = String.valueOf(BridgeConfig.DEFAULT_STALE_MESSAGE_REPLAY_LIMIT);
    private static final String[] SENSITIVE_CONFIG_KEYS = new String[]{"api_key"};
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("enabled", "1");
        DEFAULTS.put("bridge_url", GatewayEndpoint.PRODUCTION_BASE_URL);
        DEFAULTS.put("api_key", "");
        DEFAULTS.put("poll_interval_ms", DEFAULT_POLL_INTERVAL_MS);
        DEFAULTS.put("poll_limit", DEFAULT_POLL_LIMIT);
        DEFAULTS.put("outbox_websocket_enabled", DEFAULT_OUTBOX_WEBSOCKET_ENABLED);
        DEFAULTS.put("contact_sync_interval_ms", DEFAULT_CONTACT_SYNC_INTERVAL_MS);
        DEFAULTS.put("contact_sync_limit", DEFAULT_CONTACT_SYNC_LIMIT);
        DEFAULTS.put("contact_include_chatrooms", DEFAULT_CONTACT_INCLUDE_CHATROOMS);
        DEFAULTS.put("media_upload_enabled", DEFAULT_MEDIA_UPLOAD_ENABLED);
        DEFAULTS.put("media_upload_limit_bytes", DEFAULT_MEDIA_UPLOAD_LIMIT_BYTES);
        DEFAULTS.put("stale_message_grace_ms", DEFAULT_STALE_MESSAGE_GRACE_MS);
        DEFAULTS.put("stale_message_replay_limit", DEFAULT_STALE_MESSAGE_REPLAY_LIMIT);
    }

    private static final ExecutorService CONFIG_WRITER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private TextView statusView;
    private TextView connectionStatus;
    private TextView sharingStatus;
    private Properties configPreferences = new Properties();
    private Button testButton;
    private Button saveButton;
    private Button permissionButton;
    private Button cleanupButton;
    private int probeGeneration;
    private boolean probing;
    private boolean configBusy;
    private boolean configReady;
    private boolean permissionPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_title));
        setContentView(createContentView());
        loadValues();
        connectionStatus.setText("尚未测试连接。保存配置不代表微信模块已注册。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        showRuntimeStatus();
    }

    private void showRuntimeStatus() {
        if (statusView != null) statusView.setText("配置保存在当前用户的公共目录 " + SHARED_CONFIG_LOCATION
                + "。微信和本应用各授权一次目录后即可使用，无需保持 Observatory 后台。");
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        hint.setTextSize(14);
        hint.setPadding(0, 0, 0, dp(14));
        root.addView(hint);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, 0, 0, dp(14));
        root.addView(statusView);

        permissionButton = new Button(this);
        permissionButton.setText("授权配置目录");
        permissionButton.setAllCaps(false);
        permissionButton.setOnClickListener(v -> requestDirectoryPermission());
        root.addView(permissionButton);

        addField(root, "bridge_url", R.string.label_bridge_url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addField(root, "api_key", R.string.label_api_key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maskSensitiveFields();
        sharingStatus = new TextView(this);
        sharingStatus.setTextSize(14);
        sharingStatus.setTextIsSelectable(true);
        sharingStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sharingStatus.setPadding(0, dp(14), 0, dp(8));
        root.addView(sharingStatus);
        connectionStatus = new TextView(this);
        connectionStatus.setText("尚未测试连接。保存配置不代表微信模块已注册。");
        connectionStatus.setTextSize(15);
        connectionStatus.setTextIsSelectable(true);
        connectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionStatus.setPadding(0, dp(14), 0, dp(8));
        root.addView(connectionStatus);
        testButton = new Button(this);
        testButton.setText("保存并测试连接");
        testButton.setAllCaps(false);
        testButton.setOnClickListener(v -> testConnection());
        root.addView(testButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addField(root, "poll_interval_ms", R.string.label_poll_interval, InputType.TYPE_CLASS_NUMBER);
        addField(root, "poll_limit", R.string.label_poll_limit, InputType.TYPE_CLASS_NUMBER);
        addField(root, "outbox_websocket_enabled", R.string.label_outbox_websocket_enabled, InputType.TYPE_CLASS_NUMBER);
        addField(root, "contact_sync_interval_ms", R.string.label_contact_sync_interval, InputType.TYPE_CLASS_NUMBER);
        addField(root, "contact_sync_limit", R.string.label_contact_sync_limit, InputType.TYPE_CLASS_NUMBER);
        addField(root, "contact_include_chatrooms", R.string.label_contact_include_chatrooms, InputType.TYPE_CLASS_NUMBER);
        addField(root, "media_upload_enabled", R.string.label_media_upload_enabled, InputType.TYPE_CLASS_NUMBER);
        addField(root, "media_upload_limit_bytes", R.string.label_media_upload_limit, InputType.TYPE_CLASS_NUMBER);
        addField(root, "stale_message_grace_ms", R.string.label_stale_message_grace, InputType.TYPE_CLASS_NUMBER);
        addField(root, "stale_message_replay_limit", R.string.label_stale_message_replay_limit, InputType.TYPE_CLASS_NUMBER);

        saveButton = new Button(this);
        saveButton.setText(R.string.action_save);
        saveButton.setAllCaps(false);
        saveButton.setOnClickListener(v -> saveValues(false));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(12);
        root.addView(saveButton, saveParams);

        cleanupButton = new Button(this);
        cleanupButton.setText("清理旧版配置副本");
        cleanupButton.setAllCaps(false);
        cleanupButton.setOnClickListener(v -> {
            if (!configReady || configBusy || probing) return;
            setConfigBusy(true);
            sharingStatus.setText("正在清理旧版副本；如需删除旧版 root 文件，系统可能请求 root 授权…");
            Context context = getApplicationContext();
            WeakReference<SettingsActivity> owner = new WeakReference<>(this);
            CONFIG_WRITER.execute(() -> {
                String cleanupResult;
                try {
                    SharedConfigStore.migratePrivate(context);
                    Properties confirmed = SharedConfigStore.read(context);
                    confirmed.clear(); // Verify the public copy before removing any legacy copies.
                    cleanupResult = LegacyConfigCleanup.cleanup(context, true);
                } catch (java.io.IOException | RuntimeException failure) {
                    cleanupResult = "旧配置尚未安全迁移，已取消清理，请检查存储空间后重试。";
                }
                final String result = cleanupResult;
                MAIN.post(() -> {
                    SettingsActivity target = live(owner);
                    if (target == null) return;
                    target.sharingStatus.setText(result);
                    target.setConfigBusy(false);
                });
            });
        });
        root.addView(cleanupButton);

        TextView footer = new TextView(this);
        footer.setText("两端请选择系统文件选择器中的 Documents 或 Documents/WeChatObservatory。"
                + "在此修改后，可到微信网关设置重新读取。连接测试不会发送消息。"
                + "\n卸载后请删除 Documents/WeChatObservatory 文件夹。");
        footer.setTextSize(13);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        return scrollView;
    }

    private void addField(LinearLayout root, String key, int labelResId, int inputType) {
        TextView label = new TextView(this);
        label.setText(labelResId);
        label.setTextSize(14);
        label.setPadding(0, dp(10), 0, dp(4));
        root.addView(label);

        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setSaveEnabled(false);
        editText.setInputType(inputType);
        editText.setTextSize(15);
        editText.setSelectAllOnFocus(false);
        root.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        fields.put(key, editText);
        if ("bridge_url".equals(key) || "api_key".equals(key)) {
            editText.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!probing && connectionStatus != null) connectionStatus.setText("地址或密钥已修改，请保存并重新测试连接。");
                }
                public void afterTextChanged(Editable value) { }
            });
        }
    }

    private void maskSensitiveFields() {
        for (String key : SENSITIVE_CONFIG_KEYS) {
            EditText field = fields.get(key);
            if (field != null) {
                field.setTransformationMethod(PasswordTransformationMethod.getInstance());
            }
        }
    }

    private void loadValues() {
        configReady = false;
        if (!SharedConfigFiles.hasPermission(this)) {
            configPreferences.clear();
            applyValues(new Properties());
            sharingStatus.setText("先点“授权配置目录”，在系统文件选择器中选择 Documents 或 Documents/WeChatObservatory，并允许访问。授权后才能读取、保存和测试。");
            setConfigBusy(false);
            return;
        }
        setConfigBusy(true);
        sharingStatus.setText("正在读取公共目录配置…");
        Context context = getApplicationContext();
        WeakReference<SettingsActivity> owner = new WeakReference<>(this);
        CONFIG_WRITER.execute(() -> {
            Properties loaded = null;
            String failureMessage = null;
            try {
                SharedConfigStore.migratePrivate(context);
                loaded = SharedConfigStore.read(context);
            } catch (java.io.IOException | RuntimeException failure) {
                failureMessage = "公共目录配置读取或旧配置迁移失败，请检查目录授权和存储空间后重新授权；尚未覆盖现有配置。";
            }
            Properties result = loaded;
            String error = failureMessage;
            MAIN.post(() -> {
                SettingsActivity target = live(owner);
                if (target == null) {
                    if (result != null) result.clear();
                    return;
                }
                if (result == null) {
                    target.sharingStatus.setText(error);
                } else {
                    target.configPreferences.clear();
                    target.configPreferences = result;
                    target.applyValues(result);
                    target.configReady = true;
                    target.showSharingStatus(false);
                    target.connectionStatus.setText("尚未测试连接。保存配置不代表微信模块已注册。");
                }
                target.setConfigBusy(false);
            });
        });
    }

    private void applyValues(Properties prefs) {
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            if (fields.containsKey(key)) {
                setValue(key, prefs.getProperty(key, DEFAULTS.get(key)));
            }
        }
        maskSensitiveFields();
    }

    private void requestDirectoryPermission() {
        if (configBusy || probing || permissionPending) return;
        try {
            permissionPending = true;
            updateControls();
            startActivityForResult(SharedConfigFiles.permissionIntent(), DIRECTORY_PERMISSION_REQUEST);
        } catch (RuntimeException unavailable) {
            permissionPending = false;
            sharingStatus.setText("无法打开系统目录选择器，请确认系统文件应用可用后重试。");
            updateControls();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != DIRECTORY_PERMISSION_REQUEST || !permissionPending) return;
        permissionPending = false;
        if (resultCode != RESULT_OK || data == null) {
            sharingStatus.setText("已取消目录授权。请选择 Documents 或 Documents/WeChatObservatory 后允许访问。");
            updateControls();
            return;
        }
        setConfigBusy(true);
        Context context = getApplicationContext();
        WeakReference<SettingsActivity> owner = new WeakReference<>(this);
        CONFIG_WRITER.execute(() -> {
            String failureMessage = null;
            try { SharedConfigFiles.acceptPermission(context, data); }
            catch (java.io.IOException | RuntimeException failure) {
                failureMessage = "目录授权失败。请只选择本机 Documents 或 Documents/WeChatObservatory，并允许读写。";
            }
            String error = failureMessage;
            MAIN.post(() -> {
                SettingsActivity target = live(owner);
                if (target == null) return;
                if (error != null) {
                    target.sharingStatus.setText(error);
                    target.setConfigBusy(false);
                } else {
                    // Loading migrates the private copy only when the public file is absent.
                    target.loadValues();
                }
            });
        });
    }

    private void showSharingStatus(boolean saved) {
        sharingStatus.setText(saved
                ? "配置已保存到 " + SHARED_CONFIG_LOCATION + "。请在微信中打开网关设置重新读取，无需保持本应用后台。"
                : "已读取公共目录配置。微信需单独授权同一目录；划掉本应用不会中断微信内的通道。");
    }

    private void saveValues(boolean testAfterSave) {
        if (!configReady || configBusy || probing || !SharedConfigFiles.hasPermission(this)) {
            if (!SharedConfigFiles.hasPermission(this)) loadValues();
            return;
        }
        EditText bridgeUrlField = fields.get("bridge_url");
        String bridgeUrl = bridgeUrlField == null ? "" : bridgeUrlField.getText().toString().trim();
        try {
            bridgeUrl = GatewayEndpoint.normalizeBaseUrl(bridgeUrl);
        } catch (Throwable ignored) {
            if (bridgeUrlField != null) {
                bridgeUrlField.setError(getString(R.string.error_bridge_url_invalid));
                bridgeUrlField.requestFocus();
            }
            Toast.makeText(this, R.string.error_bridge_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (bridgeUrlField != null) {
            bridgeUrlField.setText(bridgeUrl);
        }

        Properties values = new Properties();
        values.putAll(ConfigWriteContract.editorSnapshot(configPreferences));
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            if ("enabled".equals(key)) {
                values.setProperty(key, "1");
                continue;
            }
            EditText field = fields.get(key);
            if (field != null) {
                String value = field.getText().toString().trim();
                if (value.isEmpty()) values.remove(key);
                else values.setProperty(key, value);
            }
        }
        maskSensitiveFields();
        setConfigBusy(true);
        connectionStatus.setText("正在保存公共目录配置…");
        Context context = getApplicationContext();
        WeakReference<SettingsActivity> owner = new WeakReference<>(this);
        CONFIG_WRITER.execute(() -> {
            Properties savedValues = null;
            String failureMessage = null;
            try {
                savedValues = SharedConfigStore.saveAndLoad(context, values, false);
            } catch (java.io.IOException | RuntimeException failure) {
                failureMessage = "公共目录配置保存结果未确认，请检查目录授权和存储空间并重新读取后再修改。";
            } finally { values.clear(); }
            Properties result = savedValues;
            String error = failureMessage;
            MAIN.post(() -> {
                SettingsActivity target = live(owner);
                if (target == null) {
                    if (result != null) result.clear();
                    return;
                }
                if (result == null) {
                    target.configReady = false;
                    target.connectionStatus.setText(error == null ? "保存结果未确认，请重新授权目录并读取。" : error);
                    target.setConfigBusy(false);
                    return;
                }
                target.configPreferences.clear();
                target.configPreferences = result;
                target.applyValues(result);
                target.showSharingStatus(true);
                target.connectionStatus.setText("配置已保存，连接尚未验证。微信中打开网关设置可重新读取。");
                target.setConfigBusy(false);
                if (testAfterSave) target.beginConnectionTest(result.getProperty("bridge_url", ""),
                        result.getProperty("api_key", ""));
                else Toast.makeText(target, "公共目录配置已保存", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void testConnection() {
        saveValues(true);
    }

    private void beginConnectionTest(String address, String key) {
        final int generation = ++probeGeneration;
        setProbing(true);
        connectionStatus.setText("正在检查后端连接…");
        WeakReference<SettingsActivity> owner = new WeakReference<>(this);
        new Thread(() -> {
            String report = "";
            try {
                GatewayEndpoint endpoint = GatewayEndpoint.parse(address);
                ConnectionProbe.Reply health = ConnectionProbe.request(endpoint, "/healthz", null);
                if (health.status != 200) {
                    report = "后端检查失败：HTTP " + health.status + "。请填写服务根地址，不要附加 /admin/。";
                } else if (!new JSONObject(health.body).optBoolean("ok", false)) {
                    report = "服务器有响应，但不是正常的微信网关健康响应。请检查地址。";
                } else {
                    report = "后端：连接成功。";
                    if (new JSONObject(health.body).has("metrics_error")) report += "\n数据库检查异常，请查看后端日志。";
                    if (key.isEmpty()) {
                        report += "\nAPI Key：未填写。请在自己的网页管理台生成。\n微信模块：尚未验证。";
                    } else {
                        String body = new JSONObject().put("api_key", key).toString();
                        ConnectionProbe.Reply result = ConnectionProbe.request(endpoint, "/module/connection", body);
                        if (result.status == 404 || result.status == 405) {
                            report += "\nAPI Key / 微信模块：当前后端不支持本模块的账号会话协议，请升级到本 fork 的 0.1.13 服务端。";
                        } else if (result.status == 401 || result.status == 403) {
                            report += "\nAPI Key：无效、已停用或请求被代理拒绝。请检查自己的管理台。\n微信模块：尚未验证。";
                        } else if (result.status != 200) {
                            report += "\n诊断失败：HTTP " + result.status + "，请查看后端日志。";
                        } else {
                            JSONObject status = new JSONObject(result.body);
                            if (!status.optBoolean("ok") || !status.optBoolean("api_key_valid")) {
                                report += "\n诊断响应异常，无法确认密钥及微信状态。";
                            } else if (!status.optBoolean("account_session_supported", false)) {
                                report += "\nAPI Key：有效，但服务器不支持账号会话确认。请升级到本 fork 的 0.1.13 服务端。";
                            } else {
                                report += "\nAPI Key：有效。设备：" + status.optString("device", "未知");
                                report += "\n" + ConnectionProbe.moduleSummary(status.optBoolean("registered"), status.optString("runtime_status"));
                                String lastPoll = status.optString("last_poll_at");
                                String lastRegister = status.optString("last_register_at");
                                if (!lastRegister.isEmpty()) report += "\n最近注册：" + lastRegister;
                                if (!lastPoll.isEmpty()) report += "\n最近拉取：" + lastPoll;
                                report += "\n以上为该密钥绑定设备的后端记录；测试不会注册设备或发送消息。";
                            }
                        }
                    }
                }
            } catch (Exception error) {
                report += (report.isEmpty() ? "" : "\n") + (error instanceof org.json.JSONException
                        ? "响应不是有效的网关 JSON，请检查服务地址及反向代理。"
                        : ConnectionProbe.describeFailure(error));
            }
            final String display = "检测时间：" + DateFormat.getDateTimeInstance().format(new Date()) + "\n" + report;
            MAIN.post(() -> {
                SettingsActivity target = live(owner);
                if (target == null || generation != target.probeGeneration) return;
                target.connectionStatus.setText(display);
                target.setProbing(false);
            });
        }, "gateway-connection-test").start();
    }

    private void setProbing(boolean value) {
        probing = value;
        testButton.setText(value ? "正在测试…" : "保存并测试连接");
        updateControls();
    }

    private void setConfigBusy(boolean value) {
        configBusy = value;
        updateControls();
    }

    private static SettingsActivity live(WeakReference<SettingsActivity> owner) {
        SettingsActivity activity = owner.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private void updateControls() {
        boolean idle = !configBusy && !probing && !permissionPending;
        boolean editable = idle && configReady && SharedConfigFiles.hasPermission(this);
        testButton.setEnabled(editable);
        saveButton.setEnabled(editable);
        cleanupButton.setEnabled(editable);
        permissionButton.setEnabled(idle);
        for (EditText field : fields.values()) field.setEnabled(editable);
    }

    @Override
    protected void onDestroy() {
        ++probeGeneration;
        configPreferences.clear();
        super.onDestroy();
    }

    private void setValue(String key, String value) {
        EditText editText = fields.get(key);
        if (editText != null) {
            editText.setText(value == null ? "" : value);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
