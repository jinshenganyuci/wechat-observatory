package cc.wechat.observatory;

import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsActivityTest {
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        activity = controller.get();
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void instructionPageHasNoConfigurationEditorAndDoesNotLaunchAnything() {
        View content = activity.findViewById(android.R.id.content);
        assertEquals(0, count(content, EditText.class));
        assertEquals(1, count(content, Button.class));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void onlyUserClickStartsWeChatWithoutConfigurationExtras() {
        Button open = findButton(activity.findViewById(android.R.id.content));
        assertNotNull(open);
        assertEquals("打开微信", open.getText().toString());

        open.performClick();

        Intent launch = shadowOf(activity).getNextStartedActivity();
        assertNotNull(launch);
        assertEquals("com.tencent.mm", launch.getComponent().getPackageName());
        assertNull(launch.getExtras());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void blockedLaunchShowsManualInstructionsWithoutCrashing() {
        final int[] attempts = {0};
        ContextWrapper blocked = new ContextWrapper(activity) {
            @Override public void startActivity(Intent intent) {
                attempts[0]++;
                throw new SecurityException("blocked by host policy");
            }
        };

        SettingsActivity.openWeChat(blocked);

        assertEquals(1, attempts[0]);
        assertEquals("无法打开微信，请从桌面打开微信后进入设置。", ShadowToast.getTextOfLatestToast());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private static int count(View view, Class<?> type) {
        int found = type.isInstance(view) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found += count(group.getChildAt(i), type);
        }
        return found;
    }

    private static Button findButton(View view) {
        if (view instanceof Button) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
