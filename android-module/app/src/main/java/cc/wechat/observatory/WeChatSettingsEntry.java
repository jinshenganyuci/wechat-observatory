package cc.wechat.observatory;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;

import cc.wechat.observatory.util.BridgeLogger;
import cc.wechat.observatory.util.HookInstallation;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Adds a local configuration entry to the verified 8.0.79 main settings page. */
public final class WeChatSettingsEntry {
    private static final String MAIN_SETTINGS =
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI";
    private static final String BASE_SETTINGS =
            "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI";
    private static final String RECYCLER = "com.tencent.mm.view.recyclerview.WxRecyclerView";
    private static final String ENTRY_TAG = "cc.wechat.observatory.settings.entry.v1";
    private static final HookInstallation INSTALLATIONS = new HookInstallation();

    // DexSQL: BaseSettingPrefUI.getLayoutId() -> 0x7f0e1384 -> r/p/ep9.xml.
    // This new page does not extend MMPreference; its existing list is untouched.
    private static final int LAYOUT_ID = 0x7f0e1384;
    private static final int ROOT_ID = 0x7f0a49f7;
    private static final int COLUMN_ID = 0x7f0a49f6;
    private static final int RECYCLER_ID = 0x7f0a58e0;

    public interface Callback {
        void onOpen(Activity activity) throws Throwable;
    }

    private WeChatSettingsEntry() { }

    /** Call with WeChat's runtime loader and installed PackageInfo version. */
    public static boolean install(ClassLoader loader, String versionName, long versionCode,
                                  Callback callback) throws Throwable {
        if (!Binding.supports(versionName, versionCode)) return false;
        if (loader == null || callback == null) throw new IllegalArgumentException("settings dependencies missing");
        final Class<?> main = Class.forName(MAIN_SETTINGS, false, loader);
        final Class<?> base = Class.forName(BASE_SETTINGS, false, loader);
        if (!Activity.class.isAssignableFrom(main)) {
            throw new IllegalStateException("settings page is not an Activity");
        }
        final Binding binding = Binding.resolve(main, base, Bundle.class);
        return INSTALLATIONS.installOnce(binding.onCreate, () -> {
            XposedBridge.hookMethod(binding.onCreate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null
                            || param.thisObject.getClass() != main) return;
                    Activity activity = (Activity) param.thisObject;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    // Base onCreate may return before a subclass finishes. Wait for
                    // that call stack without keeping a destroyed activity alive.
                    WeakReference<Activity> reference = new WeakReference<>(activity);
                    activity.getWindow().getDecorView().post(() -> {
                        Activity target = reference.get();
                        if (target == null || target.isFinishing() || target.isDestroyed()) return;
                        try {
                            if (!Integer.valueOf(LAYOUT_ID).equals(binding.getLayoutId.invoke(target))) {
                                throw new IllegalStateException("settings layout changed");
                            }
                            addEntry(target, callback);
                        } catch (Throwable error) {
                            // A missing entry must never prevent settings from opening.
                            BridgeLogger.log("settings entry unavailable: " + error.getClass().getSimpleName());
                        }
                    });
                }
            });
            BridgeLogger.log("settings entry hook installed for WeChat 8.0.79/3200");
        });
    }

    private static void addEntry(Activity activity, Callback callback) {
        requireResource(activity, LAYOUT_ID, "com.tencent.mm:layout/ep9");
        requireResource(activity, ROOT_ID, "com.tencent.mm:id/jlw");
        requireResource(activity, COLUMN_ID, "com.tencent.mm:id/jlv");
        requireResource(activity, RECYCLER_ID, "com.tencent.mm:id/lqa");
        View root = activity.findViewById(ROOT_ID);
        View columnView = activity.findViewById(COLUMN_ID);
        View list = activity.findViewById(RECYCLER_ID);
        if (!(root instanceof RelativeLayout) || !(columnView instanceof LinearLayout)
                || columnView.getParent() != root || list == null
                || !RECYCLER.equals(list.getClass().getName())
                || !(list.getParent() instanceof RelativeLayout)) {
            throw new IllegalStateException("settings view structure changed");
        }
        LinearLayout column = (LinearLayout) columnView;
        View listContainer = (View) list.getParent();
        if (column.getOrientation() != LinearLayout.VERTICAL || listContainer.getParent() != column) {
            throw new IllegalStateException("settings list parent changed");
        }
        if (column.findViewWithTag(ENTRY_TAG) != null) return;
        int listIndex = column.indexOfChild(listContainer);
        if (listIndex < 0 || !(listContainer.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || ((LinearLayout.LayoutParams) listContainer.getLayoutParams()).weight <= 0) {
            throw new IllegalStateException("settings list sizing changed");
        }

        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int foreground = themeColor(activity, android.R.attr.textColorPrimary,
                dark ? 0xffeeeeee : 0xff191919);
        int background = themeColor(activity, android.R.attr.colorBackground,
                dark ? 0xff191919 : Color.WHITE);
        LinearLayout row = new LinearLayout(activity);
        row.setTag(ENTRY_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 16));
        row.setMinimumHeight(dp(activity, 56));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("Observatory 网关，打开配置");
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000),
                new ColorDrawable(background), null));

        TextView title = new TextView(activity);
        title.setText("Observatory 网关");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(foreground);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        arrow.setTextColor(foreground);
        arrow.setAlpha(0.45f);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(view -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                callback.onOpen(activity);
            } catch (Throwable error) {
                BridgeLogger.log("settings dialog failed: " + error.getClass().getSimpleName());
                Toast.makeText(activity, "网关配置暂时无法打开，请重新进入设置。", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(activity, 8);
        column.addView(row, listIndex, params);
        BridgeLogger.log("settings entry added");
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void requireResource(Activity activity, int id, String name) {
        if (!name.equals(activity.getResources().getResourceName(id))) {
            throw new IllegalStateException("settings resource identity changed");
        }
    }

    private static int themeColor(Activity activity, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!activity.getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            try {
                return activity.getResources().getColorStateList(value.resourceId, activity.getTheme())
                        .getDefaultColor();
            } catch (RuntimeException ignored) { }
        }
        return fallback;
    }

    /** Reflection contract kept free of Android calls for JVM verification. */
    static final class Binding {
        final Method onCreate;
        final Method getLayoutId;

        private Binding(Method onCreate, Method getLayoutId) {
            this.onCreate = onCreate;
            this.getLayoutId = getLayoutId;
        }

        static boolean supports(String name, long code) {
            return "8.0.79".equals(name) && code == 3200;
        }

        static Binding resolve(Class<?> main, Class<?> base, Class<?> bundle) throws ReflectiveOperationException {
            if (main.getSuperclass() != base) throw new IllegalStateException("settings hierarchy changed");
            Method create = base.getDeclaredMethod("onCreate", bundle);
            Method layout = base.getDeclaredMethod("getLayoutId");
            if (create.getReturnType() != void.class || layout.getReturnType() != int.class
                    || Modifier.isStatic(create.getModifiers()) || Modifier.isStatic(layout.getModifiers())
                    || Modifier.isAbstract(create.getModifiers()) || Modifier.isAbstract(layout.getModifiers())) {
                throw new IllegalStateException("settings lifecycle signature changed");
            }
            create.setAccessible(true);
            layout.setAccessible(true);
            return new Binding(create, layout);
        }
    }
}
