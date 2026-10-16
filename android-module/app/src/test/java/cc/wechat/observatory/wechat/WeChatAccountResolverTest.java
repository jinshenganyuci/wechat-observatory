package cc.wechat.observatory.wechat;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

public class WeChatAccountResolverTest {
    @Before
    public void resetVersionFactories() {
        pp0.i1.calls = gp0.j1.calls = gm0.j1.calls = 0;
        Config78 current = new Config78();
        current.id = "wxid_8079";
        pp0.i1.storage = new Storage(current);
        gp0.j1.storage = new Storage(new Config78());
        gm0.j1.storage = new Storage(new Config74());
    }

    public static class Config78 {
        String id = "wxid_yuyu";
        String alias = "liuhhdsh";
        public Object m(int key, Object fallback) {
            if (key == 2) return id;
            if (key == 42) return alias;
            if (key == 4) return "雨雨";
            return fallback;
        }
    }

    public static class Config74 {
        public Object l(int key, Object fallback) {
            if (key == 2) return "a565265501";
            if (key == 4) return "兰兰";
            return fallback;
        }
    }

    public static class Storage {
        final Object config;
        String path = "/data/user/0/com.tencent.mm/MicroMsg/current/EnMicroMsg.db";
        Storage(Object config) { this.config = config; }
        public String g() { return path; }
        public Object c() { return config; }
    }

    @Test
    public void readsCurrentMmkvConfigurationWithoutSqlUserinfo() throws Exception {
        RuntimeAccount account = WeChatAccountResolver.readStorage(new Storage(new Config78()), "m");
        assertEquals("wxid_yuyu", account.wxid);
        assertEquals("雨雨", account.nickname);
    }

    @Test
    public void resolves8079WithoutCallingAnOlderFactoryEvenWhenBothExist() throws Exception {
        RuntimeAccount account = WeChatAccountResolver.resolve(getClass().getClassLoader(), "8.0.79", 3200);
        assertEquals("wxid_8079", account.wxid);
        assertEquals(1, pp0.i1.calls);
        assertEquals(0, gp0.j1.calls);
        assertEquals(0, gm0.j1.calls);
    }

    @Test
    public void resolvesBothPreviouslyVerifiedVersionsWithTheirOwnGetters() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals("wxid_yuyu", WeChatAccountResolver.resolve(loader, "8.0.78", 3180).wxid);
        assertEquals("a565265501", WeChatAccountResolver.resolve(loader, "8.0.74", 3120).wxid);
        assertEquals(0, pp0.i1.calls);
        assertEquals(1, gp0.j1.calls);
        assertEquals(1, gm0.j1.calls);
    }

    @Test
    public void missingCurrentBindingDoesNotFallBackToAnOlderAccount() throws Exception {
        ClassLoader withoutCurrentKernel = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("pp0.i1".equals(name)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        try {
            WeChatAccountResolver.resolve(withoutCurrentKernel, "8.0.79", 3200);
            fail("The current version's missing binding must fail explicitly");
        } catch (ClassNotFoundException expected) {
            assertEquals("pp0.i1", expected.getMessage());
        }
        assertEquals(0, gp0.j1.calls);
        assertEquals(0, gm0.j1.calls);
    }

    @Test
    public void unknownVersionIsRejectedBeforeLoadingAnyWechatClass() throws Exception {
        ClassLoader mustNotLoad = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("Unsupported APK must not load " + name);
            }
        };
        try {
            WeChatAccountResolver.resolve(mustNotLoad, "8.0.79", 3201);
            fail("An unverified build must not use another build's obfuscated names");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("8.0.79/3201"));
        }
    }

    @Test
    public void versionNameAndCodeMustBothMatchTheProfile() {
        for (String name : new String[]{"8.0.78", "8.0.80", "8.0.79-beta", "", null}) {
            try {
                WeChatVersionProfile.forVersion(name, 3200);
                fail("Unexpected profile for " + name + "/3200");
            } catch (UnsupportedOperationException expected) {
                assertTrue(expected.getMessage().contains("/3200"));
            }
        }
        assertTrue(WeChatVersionProfile.forVersion("8.0.79", 3200).is8079());
        assertFalse(WeChatVersionProfile.forVersion("8.0.78", 3180).is8079());
    }

    @Test
    public void accountInitializationFailureDoesNotReadConfiguration() throws Exception {
        Storage storage = new Storage(new Config78()) {
            @Override public String g() { throw new IllegalStateException("Account not initialized"); }
            @Override public Object c() { throw new AssertionError("Guard must precede config reads"); }
        };
        pp0.i1.storage = storage;
        try {
            WeChatAccountResolver.resolve(getClass().getClassLoader(), "8.0.79", 3200);
            fail("An uninitialized account must not be used");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertEquals("Account not initialized", expected.getCause().getMessage());
        }
    }

    @Test
    public void preservesOldVersionAndNonWxidAccountNames() throws Exception {
        RuntimeAccount account = WeChatAccountResolver.readStorage(new Storage(new Config74()), "l");
        assertEquals("a565265501", account.wxid);
        assertEquals("兰兰", account.nickname);
    }

    @Test(expected = IllegalStateException.class)
    public void waitsForCanonicalIdentityEvenWhenAliasExists() throws Exception {
        Config78 config = new Config78(); config.id = "";
        WeChatAccountResolver.readStorage(new Storage(config), "m");
    }

    @Test(expected = IllegalStateException.class)
    public void absentIdentityDoesNotInventDirectoryOwner() throws Exception {
        Config78 config = new Config78(); config.id = ""; config.alias = "";
        WeChatAccountResolver.readStorage(new Storage(config), "m");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAccountChangeDuringConfigRead() throws Exception {
        Storage storage = new Storage(new Config78()) {
            int reads;
            @Override public String g() {
                return ++reads == 1 ? path : path.replace("/current/", "/next/");
            }
        };
        WeChatAccountResolver.readStorage(storage, "m");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUsernameChangeEvenWhenDatabasePathStaysTheSame() throws Exception {
        Config78 config = new Config78() {
            int identityReads;
            @Override public Object m(int key, Object fallback) {
                if (key == 2) return ++identityReads == 1 ? "wxid_first" : "wxid_second";
                return super.m(key, fallback);
            }
        };
        WeChatAccountResolver.readStorage(new Storage(config), "m");
    }

    @Test
    public void displayNamesAndNumericValuesAreNotAccountIdentifiers() {
        assertFalse(WeChatAccountResolver.isAccountId("雨雨"));
        assertFalse(WeChatAccountResolver.isAccountId("12345"));
        assertFalse(WeChatAccountResolver.isAccountId("acct_123abc"));
        assertTrue(WeChatAccountResolver.isAccountId("wxid_current"));
    }
}
