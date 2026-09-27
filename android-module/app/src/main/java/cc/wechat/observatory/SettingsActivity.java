package cc.wechat.observatory;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
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

import cc.wechat.observatory.config.BridgeConfig;
import cc.wechat.observatory.gateway.GatewayEndpoint;

public final class SettingsActivity extends Activity {
    private static final String DEFAULT_POLL_INTERVAL_MS = "1000";
    private static final String DEFAULT_POLL_LIMIT = "1";
    private static final String DEFAULT_OUTBOX_WEBSOCKET_ENABLED = "0";
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

    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private TextView connectionStatus;
    private Button testButton;
    private Button saveButton;
    private int probeGeneration;
    private boolean probing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.settings_title));
        setContentView(createContentView());
        loadValues();
        connectionStatus.setText("尚未测试连接。保存配置不代表微信模块已注册。");
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

        addField(root, "bridge_url", R.string.label_bridge_url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addField(root, "api_key", R.string.label_api_key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        maskSensitiveFields();
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
        saveButton.setOnClickListener(v -> saveValues());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(12);
        root.addView(saveButton, saveParams);

        TextView footer = new TextView(this);
        footer.setText(R.string.settings_restart_hint);
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
        SharedPreferences prefs = getSharedPreferences(BridgeConfigProvider.PREFS_NAME, Context.MODE_PRIVATE);
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            if (fields.containsKey(key)) {
                setValue(key, prefs.getString(key, DEFAULTS.get(key)));
            }
        }
    }

    private boolean saveValues() {
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
            return false;
        }
        if (bridgeUrlField != null) {
            bridgeUrlField.setText(bridgeUrl);
        }

        SharedPreferences prefs = getSharedPreferences(BridgeConfigProvider.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            if ("enabled".equals(key)) {
                editor.putString(key, "1");
                continue;
            }
            EditText field = fields.get(key);
            if (field != null) {
                String value = field.getText().toString().trim();
                if (value.isEmpty()) {
                    editor.remove(key);
                } else {
                    editor.putString(key, value);
                }
            }
        }
        if (!editor.commit()) {
            connectionStatus.setText("配置保存失败，请检查手机存储空间后重试。");
            return false;
        }
        Context appContext = getApplicationContext();
        CONFIG_WRITER.execute(() -> BridgeConfigFiles.writeExternalMirror(appContext, prefs));
        maskSensitiveFields();
        connectionStatus.setText("配置已保存，连接尚未验证。请重启微信后测试连接。");
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show();
        return true;
    }

    private void testConnection() {
        if (probing || !saveValues()) return;
        final String address = fields.get("bridge_url").getText().toString().trim();
        final String key = fields.get("api_key").getText().toString().trim();
        final int generation = ++probeGeneration;
        setProbing(true);
        connectionStatus.setText("正在检查后端连接…");
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
                        ConnectionProbe.Reply result = ConnectionProbe.request(endpoint, "/module/diagnostics", body);
                        if (result.status == 404 || result.status == 405) {
                            report += "\nAPI Key / 微信模块：当前后端版本不支持诊断，请升级到 v0.1.3-fork.2 或更新版本。";
                        } else if (result.status == 401 || result.status == 403) {
                            report += "\nAPI Key：无效、已停用或请求被代理拒绝。请检查自己的管理台。\n微信模块：尚未验证。";
                        } else if (result.status != 200) {
                            report += "\n诊断失败：HTTP " + result.status + "，请查看后端日志。";
                        } else {
                            JSONObject status = new JSONObject(result.body);
                            if (!status.optBoolean("ok") || !status.optBoolean("api_key_valid")) {
                                report += "\n诊断响应异常，无法确认密钥及微信状态。";
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
            runOnUiThread(() -> {
                if (generation != probeGeneration || isFinishing() || isDestroyed()) return;
                connectionStatus.setText(display);
                setProbing(false);
            });
        }, "gateway-connection-test").start();
    }

    private void setProbing(boolean value) {
        probing = value;
        testButton.setEnabled(!value);
        testButton.setText(value ? "正在测试…" : "保存并测试连接");
        saveButton.setEnabled(!value);
        for (EditText field : fields.values()) field.setEnabled(!value);
    }

    @Override
    protected void onDestroy() {
        ++probeGeneration;
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
