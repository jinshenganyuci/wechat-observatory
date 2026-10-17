package cc.wechat.observatory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Switch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import cc.wechat.observatory.config.BridgeConfig;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WeChatConfigUiTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private Object session;
    private AlertDialog dialog;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After public void tearDown() {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        if (latest != null) latest.dismiss();
        if (dialog != null) dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
        BridgeConfig.clearRuntimeSettings();
    }

    @Test public void freshEditorStartsWithEmptyFieldsAndGatewayDisabled() throws Exception {
        open(new LinkedHashMap<>());

        assertEquals("", field("url", EditText.class).getText().toString());
        assertEquals("", field("key", EditText.class).getText().toString());
        assertFalse(field("enabled", Switch.class).isChecked());
        assertFalse(field("backgroundRealtime", Switch.class).isChecked());
        assertEquals("停用并删除配置", dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
        assertNull("host switch backgrounds must stay isolated", field("enabled", Switch.class).getBackground());
    }

    @Test public void deleteConfirmationLeavesEditorOpenAndCancelPreservesValues() throws Exception {
        open(configured());

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();

        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(dialog, confirmation);
        assertTrue(dialog.isShowing());
        assertTrue(confirmation.isShowing());
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("dummy-key", field("key", EditText.class).getText().toString());
        assertTrue(field("enabled", Switch.class).isChecked());
    }

    @Test public void confirmedDeletionPresentationClearsFieldsAndDisablesGateway() throws Exception {
        Map<String, String> snapshot = configured();
        open(snapshot);

        // The production callback invokes this only after the client confirms deletion.
        invoke("clearSnapshot");

        assertTrue(snapshot.isEmpty());
        assertEquals("", field("url", EditText.class).getText().toString());
        assertEquals("", field("key", EditText.class).getText().toString());
        assertFalse(field("enabled", Switch.class).isChecked());
        assertFalse(field("backgroundRealtime", Switch.class).isChecked());
        assertTrue(dialog.isShowing());
    }

    @Test public void protectionPreferenceLoadsAndSerializesAlongsideGatewayDisable() throws Exception {
        Map<String, String> snapshot = configured();
        open(snapshot);
        assertTrue(field("backgroundRealtime", Switch.class).isChecked());
        assertEquals("Oplus 息屏保护", field("backgroundRealtime", Switch.class).getContentDescription());

        field("enabled", Switch.class).setChecked(false);
        field("websocket", Switch.class).setChecked(false);
        Map<String, String> values = valuesForSave();
        Map<String, String> normalized = ConfigWriteContract.normalize(values);
        assertEquals("0", normalized.get("enabled"));
        assertEquals("0", normalized.get("outbox_websocket_enabled"));
        assertEquals("1", normalized.get("background_realtime_enabled"));
        assertEquals("unsaved controls must not overwrite the loaded snapshot", "1", snapshot.get("enabled"));

        field("backgroundRealtime", Switch.class).setChecked(false);
        assertEquals("0", valuesForSave().get("background_realtime_enabled"));
    }

    @Test public void refreshAndBusyStateCoverTheProtectionSwitch() throws Exception {
        open(configured());
        Properties saved = new Properties();
        saved.setProperty("enabled", "0");
        saved.setProperty("background_realtime_enabled", "0");
        BridgeConfig.acceptRuntimeSettings(saved);
        invoke("refreshSnapshot");
        assertFalse(field("backgroundRealtime", Switch.class).isChecked());

        Method setSaving = session.getClass().getDeclaredMethod("setSaving", boolean.class);
        setSaving.setAccessible(true);
        setSaving.invoke(session, true);
        assertFalse(field("backgroundRealtime", Switch.class).isEnabled());
        setSaving.invoke(session, false);
        assertTrue(field("backgroundRealtime", Switch.class).isEnabled());
    }

    @Test public void unreadableConfigurationOffersDeleteAndCancelDoesNotStartFileClient() throws Exception {
        Field shared = ConfigFileClient.class.getDeclaredField("shared");
        shared.setAccessible(true);
        assertNull(shared.get(null));
        Method showFailure = WeChatConfigUi.class.getDeclaredMethod("showReadFailure", Activity.class, String.class);
        showFailure.setAccessible(true);

        showFailure.invoke(null, activity, "配置内容无效");
        AlertDialog failure = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("停用并删除配置", failure.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
        failure.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(failure, confirmation);
        assertTrue(confirmation.isShowing());
        assertEquals("停用并删除", confirmation.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(confirmation.isShowing());
        assertNull("cancel must not even start a configuration operation", shared.get(null));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void closingEditorDropsItsConfigurationSnapshot() throws Exception {
        Map<String, String> snapshot = configured();
        open(snapshot);

        dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(snapshot.isEmpty());
        assertEquals("", field("key", EditText.class).getText().toString());
        assertFalse(field("key", EditText.class).isSaveEnabled());
    }

    private void open(Map<String, String> snapshot) throws Exception {
        Class<?> type = Class.forName(WeChatConfigUi.class.getName() + "$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(Activity.class, Map.class, String.class);
        constructor.setAccessible(true);
        session = constructor.newInstance(activity, snapshot, "测试状态");
        dialog = field("dialog", AlertDialog.class);
        invoke("show");
    }

    private void invoke(String name) throws Exception {
        Method method = session.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> valuesForSave() throws Exception {
        Method method = session.getClass().getDeclaredMethod("valuesForSave");
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(session);
    }

    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = session.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(session));
    }

    private static Map<String, String> configured() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bridge_url", "https://example.invalid/");
        values.put("api_key", "dummy-key");
        values.put("enabled", "1");
        values.put("background_realtime_enabled", "1");
        return values;
    }
}
