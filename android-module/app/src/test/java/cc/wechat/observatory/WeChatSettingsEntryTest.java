package cc.wechat.observatory;

import org.junit.Test;

import static org.junit.Assert.*;

public class WeChatSettingsEntryTest {
    public static class BundleFixture { }
    public static class SettingsBase {
        public void onCreate(BundleFixture bundle) { }
        public int getLayoutId() { return 0x7f0e1384; }
    }
    public static class MainSettings extends SettingsBase { }
    public static class OtherBase { }
    public static class StaticBase {
        public static void onCreate(BundleFixture bundle) { }
        public int getLayoutId() { return 1; }
    }
    public static class StaticMain extends StaticBase { }
    public static class WrongReturnBase {
        public boolean onCreate(BundleFixture bundle) { return true; }
        public int getLayoutId() { return 1; }
    }
    public static class WrongReturnMain extends WrongReturnBase { }
    public static class InheritedBase extends SettingsBase { }
    public static class InheritedMain extends InheritedBase { }

    @Test public void onlyExactAuditedVersionEnablesResourceIds() {
        assertTrue(WeChatSettingsEntry.Binding.supports("8.0.79", 3200));
        assertTrue(WeChatSettingsEntry.Binding.supports("8.0.80", 3220));
        assertFalse(WeChatSettingsEntry.Binding.supports("8.0.80", 3221));
        assertFalse(WeChatSettingsEntry.Binding.supports("8.0.79", 3201));
        assertFalse(WeChatSettingsEntry.Binding.supports("8.0.80", 3200));
        assertFalse(WeChatSettingsEntry.Binding.supports(null, 3200));
    }

    @Test public void resourceBindingsStaySeparateAcrossVersions() {
        WeChatSettingsEntry.Layout old = WeChatSettingsEntry.Layout.forVersion("8.0.79", 3200);
        WeChatSettingsEntry.Layout current = WeChatSettingsEntry.Layout.forVersion("8.0.80", 3220);
        assertEquals(0x7f0e1384, old.page);
        assertEquals(0x7f0e1380, current.page);
        assertEquals(0x7f0a48fd, current.root);
        assertEquals(0x7f0a48fc, current.column);
        assertEquals(0x7f0a578b, current.recycler);
        assertEquals("ept", current.name);
        assertNull(WeChatSettingsEntry.Layout.forVersion("8.0.79", 3220));
    }

    @Test public void bindsOnlyTheExpectedDeclaredLifecycleMethod() throws Exception {
        WeChatSettingsEntry.Binding binding = WeChatSettingsEntry.Binding.resolve(
                MainSettings.class, SettingsBase.class, BundleFixture.class);
        assertSame(SettingsBase.class, binding.onCreate.getDeclaringClass());
        assertEquals(void.class, binding.onCreate.getReturnType());
        assertEquals(0x7f0e1384, binding.getLayoutId.invoke(new MainSettings()));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDifferentPageHierarchy() throws Exception {
        WeChatSettingsEntry.Binding.resolve(MainSettings.class, OtherBase.class, BundleFixture.class);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsStaticLifecycleCollision() throws Exception {
        WeChatSettingsEntry.Binding.resolve(StaticMain.class, StaticBase.class, BundleFixture.class);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsWrongLifecycleReturnType() throws Exception {
        WeChatSettingsEntry.Binding.resolve(WrongReturnMain.class, WrongReturnBase.class, BundleFixture.class);
    }

    @Test(expected = NoSuchMethodException.class)
    public void doesNotHookBroaderInheritedActivityMethod() throws Exception {
        WeChatSettingsEntry.Binding.resolve(InheritedMain.class, InheritedBase.class, BundleFixture.class);
    }
}
