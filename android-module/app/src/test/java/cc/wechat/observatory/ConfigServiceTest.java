package cc.wechat.observatory;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConfigServiceTest {
    private static final int WECHAT_UID = 20001;
    private ServiceController<ConfigService> controller;
    private ConfigService service;
    private IBinder binder;
    private FakeStore store;

    @Before public void setUp() {
        controller = Robolectric.buildService(ConfigService.class).create();
        service = controller.get();
        PackageInfo wechat = new PackageInfo();
        wechat.packageName = "com.tencent.mm";
        wechat.applicationInfo = new ApplicationInfo();
        wechat.applicationInfo.packageName = wechat.packageName;
        wechat.applicationInfo.uid = WECHAT_UID;
        Shadows.shadowOf(service.getPackageManager()).installPackage(wechat);
        ShadowBinder.setCallingUid(WECHAT_UID);
        store = new FakeStore();
        service.store = store;
        binder = service.onBind(new Intent());
    }

    @After public void tearDown() {
        ShadowBinder.reset();
        controller.destroy();
    }

    @Test public void exactCurrentUserWechatUidIsRequiredOnEveryTransaction() throws Exception {
        assertEquals(ConfigBinderProtocol.OK, call(true, false, Collections.emptyMap()).status);
        assertEquals(1, store.loads);
        for (int unauthorized : new int[]{WECHAT_UID + 1, WECHAT_UID + 100000, -1}) {
            ShadowBinder.setCallingUid(unauthorized);
            assertFailure(ConfigBinderProtocol.DENIED, call(false, true, configured()));
        }
        assertEquals(1, store.loads);
        assertEquals(0, store.saves);
    }

    @Test public void missingWechatPackageFailsClosed() throws Exception {
        Shadows.shadowOf(service.getPackageManager()).removePackage("com.tencent.mm");
        assertFailure(ConfigBinderProtocol.DENIED, call(true, false, Collections.emptyMap()));
        assertEquals(0, store.loads);
    }

    @Test public void editorReadDoesNotReserveRuntimeSession() throws Exception {
        ConfigBinderProtocol.Response editor = call(true, false, Collections.emptyMap());
        assertEquals(ConfigBinderProtocol.OK, editor.status);
        assertFalse(store.lastNeedsSession);
        assertFalse(editor.values.containsKey("__installation_id"));
        ConfigBinderProtocol.Response runtime = call(true, true, Collections.emptyMap());
        assertEquals(ConfigBinderProtocol.OK, runtime.status);
        assertTrue(store.lastNeedsSession);
        assertTrue(runtime.values.containsKey("__generation_start"));
        assertEquals(0, store.saves);
    }

    @Test public void saveNormalizesAndReturnsSameCommittedRevisionUnderStoreLock() throws Exception {
        Map<String, String> values = configured();
        values.put("api_key", "  sample-test-key  ");
        ConfigBinderProtocol.Response response = call(false, true, values);
        assertEquals(ConfigBinderProtocol.OK, response.status);
        assertEquals("sample-test-key", store.saved.getProperty("api_key"));
        assertEquals("1", store.saved.getProperty("enabled"));
        assertEquals("1", store.saved.getProperty("outbox_websocket_enabled"));
        assertEquals(store.revision, response.values.get(ConfigWriteContract.REVISION_KEY));
        assertEquals(1, store.saves);
        assertEquals(1, store.loads);
        assertTrue(store.lastNeedsSession);
    }

    @Test public void emptySaveClearsBusinessValuesButStillReturnsRevision() throws Exception {
        ConfigBinderProtocol.Response response = call(false, false, Collections.emptyMap());
        assertEquals(ConfigBinderProtocol.OK, response.status);
        assertTrue(store.saved.isEmpty());
        assertEquals(1, response.values.size());
        assertEquals(store.revision, response.values.get(ConfigWriteContract.REVISION_KEY));
    }

    @Test public void invalidSaveNeverTouchesStore() throws Exception {
        Map<String, String> values = configured();
        values.put("enabled", "unexpected");
        assertFailure(ConfigBinderProtocol.INVALID, call(false, true, values));
        assertEquals(0, store.saves);
        assertEquals(0, store.loads);
    }

    @Test public void deadlineIsCheckedUnderLockBeforeAnyPersistence() throws Exception {
        ConfigBinderProtocol.Request request = ConfigBinderProtocolTest.request(false, true, configured());
        service.clock = () -> {
            assertTrue(Thread.holdsLock(ModuleConfigStore.class));
            return request.deadlineElapsedMs;
        };
        assertFailure(ConfigBinderProtocol.EXPIRED, ConfigBinderProtocol.transact(binder, request));
        assertEquals(0, store.saves);
        assertEquals(0, store.loads);
    }

    @Test public void arbitraryLongDeadlineIsRejected() throws Exception {
        long now = SystemClock.elapsedRealtime();
        service.clock = () -> now;
        ConfigBinderProtocol.Request request = new ConfigBinderProtocol.Request(false, true, configured(),
                now + ConfigBinderProtocol.TIMEOUT_MS + 1);
        assertFailure(ConfigBinderProtocol.INVALID, ConfigBinderProtocol.transact(binder, request));
        assertEquals(0, store.saves);
    }

    @Test public void failedSaveDoesNotReadOrDisclosePreviousSnapshot() throws Exception {
        store.failSave = true;
        assertFailure(ConfigBinderProtocol.FAILED, call(false, false, configured()));
        assertEquals(1, store.saves);
        assertEquals(0, store.loads);
    }

    @Test public void failedReadAndMismatchedRevisionReturnNoValues() throws Exception {
        store.failRead = true;
        assertFailure(ConfigBinderProtocol.FAILED, call(true, true, Collections.emptyMap()));
        store.failRead = false;
        store.mismatchRevision = true;
        assertFailure(ConfigBinderProtocol.FAILED, call(false, true, configured()));
    }

    @Test public void missingSessionCannotBeReportedAsSuccessfulLoad() throws Exception {
        store.omitSession = true;
        assertFailure(ConfigBinderProtocol.FAILED, call(true, true, Collections.emptyMap()));
    }

    @Test public void malformedRequestIsRejectedBeforeStoreRead() throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        ConfigBinderProtocol.Request request = ConfigBinderProtocolTest.request(true, false, Collections.emptyMap());
        try {
            ConfigBinderProtocol.writeRequest(data, request);
            data.writeInt(123); // A valid prefix must not authorize trailing payload.
            assertTrue(binder.transact(ConfigBinderProtocol.TRANSACTION_CONFIG, data, reply, 0));
            assertFailure(ConfigBinderProtocol.INVALID, ConfigBinderProtocol.readResponse(reply, request));
            assertEquals(0, store.loads);
        } finally { reply.recycle(); data.recycle(); }
    }

    @Test public void unsupportedAndOneWayRequestsCannotWrite() throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            ConfigBinderProtocol.writeRequest(data, ConfigBinderProtocolTest.request(false, false, configured()));
            assertFalse(binder.transact(ConfigBinderProtocol.TRANSACTION_CONFIG + 1, data, reply, 0));
            assertFalse(binder.transact(ConfigBinderProtocol.TRANSACTION_CONFIG, data, reply, IBinder.FLAG_ONEWAY));
            assertEquals(0, store.saves);
            assertEquals(0, store.loads);
        } finally { reply.recycle(); data.recycle(); }
    }

    @Test public void accidentalStartDoesNotRequestStickyService() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 1));
    }

    private ConfigBinderProtocol.Response call(boolean loading, boolean session, Map<String, String> values) throws Exception {
        return ConfigBinderProtocol.transact(binder, ConfigBinderProtocolTest.request(loading, session, values));
    }

    private static void assertFailure(String expected, ConfigBinderProtocol.Response actual) {
        assertEquals(expected, actual.status);
        assertTrue(actual.values.isEmpty());
    }

    private static Map<String, String> configured() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bridge_url", "https://example.test/gateway");
        values.put("api_key", "test-key");
        return values;
    }

    private static final class FakeStore implements ConfigService.Store {
        final String revision = "12345678-1234-1234-1234-123456789abc";
        int saves;
        int loads;
        boolean lastNeedsSession;
        boolean failSave;
        boolean failRead;
        boolean mismatchRevision;
        boolean omitSession;
        Properties saved;

        @Override public ModuleConfigStore.SaveResult save(Context context, Properties settings) {
            assertTrue(Thread.holdsLock(ModuleConfigStore.class));
            saves++;
            saved = new Properties();
            saved.putAll(settings);
            return new ModuleConfigStore.SaveResult(!failSave, revision, "");
        }

        @Override public Properties load(Context context, boolean needsSession) throws IOException {
            assertTrue(Thread.holdsLock(ModuleConfigStore.class));
            loads++;
            lastNeedsSession = needsSession;
            if (failRead) throw new IOException("should never cross Binder");
            Properties snapshot = new Properties();
            if (saved != null) snapshot.putAll(saved);
            snapshot.putAll(ConfigBinderProtocolTest.snapshot(needsSession && !omitSession));
            if (mismatchRevision) snapshot.setProperty(ConfigWriteContract.REVISION_KEY,
                    "87654321-4321-4321-4321-abcdef123456");
            return snapshot;
        }
    }
}
