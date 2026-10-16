package cc.wechat.observatory;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConfigBinderProtocolTest {
    @Test public void stringRequestRoundTripsAndOwnsItsMap() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("api_key", "测试-token");
        ConfigBinderProtocol.Request request = request(false, true, input);
        input.clear();
        Parcel data = Parcel.obtain();
        try {
            ConfigBinderProtocol.writeRequest(data, request);
            data.setDataPosition(0);
            ConfigBinderProtocol.Request decoded = ConfigBinderProtocol.readRequest(data);
            assertFalse(decoded.loading);
            assertTrue(decoded.needsSession);
            assertEquals(request.deadlineElapsedMs, decoded.deadlineElapsedMs);
            assertEquals(request.nonce, decoded.nonce);
            assertEquals("测试-token", decoded.values.get("api_key"));
            decoded.clear();
            assertTrue(decoded.values.isEmpty());
            assertFalse(request.values.isEmpty());
            request.clear();
        } finally { data.recycle(); }
    }

    @Test @Config(sdk = 23) public void emptySaveRoundTripsOnMinimumSdk() {
        ConfigBinderProtocol.Request request = request(false, false, Collections.emptyMap());
        Parcel data = Parcel.obtain();
        try {
            ConfigBinderProtocol.writeRequest(data, request);
            data.setDataPosition(0);
            ConfigBinderProtocol.Request decoded = ConfigBinderProtocol.readRequest(data);
            assertFalse(decoded.loading);
            assertTrue(decoded.values.isEmpty());
        } finally { data.recycle(); }
    }

    @Test public void rejectsUnknownVersionInvalidNonceAndNonBooleanFlags() {
        rejectRequest(p -> header(p, 2, UUID.randomUUID().toString()));
        rejectRequest(p -> header(p, 1, "not-a-uuid"));
        rejectRequest(p -> {
            header(p, 1, UUID.randomUUID().toString());
            p.writeLong(1000);
            p.writeInt(2);
            p.writeInt(0);
            p.writeInt(0);
        });
    }

    @Test public void rejectsForbiddenKeysDuplicateKeysAndNullValues() {
        rejectRequest(p -> { requestHeader(p, false); entries(p, "__installation_id", "injected"); });
        rejectRequest(p -> { requestHeader(p, false); entries(p, "api_key", "one", "api_key", "two"); });
        rejectRequest(p -> { requestHeader(p, false); entries(p, "api_key", null); });
    }

    @Test public void rejectsReadWithValuesAndTrailingPayload() {
        rejectRequest(p -> { requestHeader(p, true); entries(p, "api_key", "unexpected"); });
        rejectRequest(p -> { requestHeader(p, false); entries(p); p.writeString("trailing"); });
    }

    @Test public void rejectsTruncatedCountsAndOversizedInputsBeforeUse() {
        rejectRequest(p -> { requestHeader(p, false); p.writeInt(-1); });
        rejectRequest(p -> { requestHeader(p, false); p.writeInt(Integer.MAX_VALUE); });
        rejectRequest(p -> { requestHeader(p, false); p.writeInt(1); });
        rejectRequest(p -> { requestHeader(p, false); entries(p, "api_key", repeated(4097)); });
        rejectRequest(p -> p.writeString(repeated(ConfigBinderProtocol.MAX_PARCEL_BYTES)));
    }

    @Test public void aggregateLengthIsBoundedEvenWhenEachValueFits() {
        rejectRequest(p -> {
            requestHeader(p, false);
            entries(p, "api_key", repeated(4096), "bridge_url", repeated(4096),
                    "poll_interval_ms", repeated(4096), "poll_limit", repeated(4096), "enabled", "1");
        });
    }

    @Test public void mutableRequestIsRevalidatedBeforeSending() {
        ConfigBinderProtocol.Request request = request(false, false, Collections.emptyMap());
        request.values.put("unexpected_key", "value");
        Parcel data = Parcel.obtain();
        try { assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.writeRequest(data, request)); }
        finally { data.recycle(); }
    }

    @Test public void successRequiresRevisionAndRequestedSessionTuple() {
        assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.success(Collections.emptyMap(), false));
        Map<String, String> values = snapshot(false);
        assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.success(values, true));
        values.putAll(snapshot(true));
        assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.success(values, false));
        values.put("__generation_limit", "0");
        assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.success(values, true));
    }

    @Test public void responseRejectsMismatchedNonceAndSecretBearingFailure() {
        ConfigBinderProtocol.Request request = request(true, false, Collections.emptyMap());
        rejectResponse(request, p -> response(p, UUID.randomUUID().toString(), ConfigBinderProtocol.OK, snapshot(false)));
        rejectResponse(request, p -> response(p, request.nonce, ConfigBinderProtocol.FAILED,
                Collections.singletonMap("api_key", "must-not-leak")));
        rejectResponse(request, p -> response(p, request.nonce, ConfigBinderProtocol.OK, Collections.emptyMap()));
        rejectResponse(request, p -> response(p, request.nonce, ConfigBinderProtocol.OK, snapshot(true)));
        rejectResponse(request, p -> {
            response(p, request.nonce, ConfigBinderProtocol.OK, snapshot(false));
            p.writeInt(7);
        });
    }

    @Test public void deniedBeforeParsingCanUseEmptyNonceWithoutReturningValues() {
        ConfigBinderProtocol.Request request = request(true, false, Collections.emptyMap());
        Parcel reply = Parcel.obtain();
        try {
            ConfigBinderProtocol.writeResponse(reply, "", ConfigBinderProtocol.failure(ConfigBinderProtocol.DENIED));
            reply.setDataPosition(0);
            ConfigBinderProtocol.Response response = ConfigBinderProtocol.readResponse(reply, request);
            assertEquals(ConfigBinderProtocol.DENIED, response.status);
            assertTrue(response.values.isEmpty());
        } finally { reply.recycle(); }
    }

    @Test public void actualBinderTransactionDecodesAndValidatesReply() throws Exception {
        ConfigBinderProtocol.Request original = request(true, true, Collections.emptyMap());
        Binder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                assertEquals(ConfigBinderProtocol.TRANSACTION_CONFIG, code);
                ConfigBinderProtocol.Request received = ConfigBinderProtocol.readRequest(data);
                ConfigBinderProtocol.writeResponse(reply, received.nonce, ConfigBinderProtocol.success(snapshot(true), true));
                received.clear();
                return true;
            }
        };
        ConfigBinderProtocol.Response response = ConfigBinderProtocol.transact(binder, original);
        assertEquals(ConfigBinderProtocol.OK, response.status);
        assertEquals("1", response.values.get("__generation_start"));
        response.clear();
        assertTrue(response.values.isEmpty());
    }

    @Test public void unsupportedTransactionCannotLookLikeSuccess() {
        Binder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) { return false; }
        };
        assertThrows(RemoteException.class, () -> ConfigBinderProtocol.transact(binder,
                request(true, false, Collections.emptyMap())));
    }

    private interface Writer { void write(Parcel parcel); }

    private static void rejectRequest(Writer writer) {
        Parcel data = Parcel.obtain();
        try {
            writer.write(data);
            data.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.readRequest(data));
        } finally { data.recycle(); }
    }

    private static void rejectResponse(ConfigBinderProtocol.Request request, Writer writer) {
        Parcel data = Parcel.obtain();
        try {
            writer.write(data);
            data.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> ConfigBinderProtocol.readResponse(data, request));
        } finally { data.recycle(); }
    }

    private static void header(Parcel p, int version, String nonce) {
        p.writeString(ConfigBinderProtocol.DESCRIPTOR);
        p.writeInt(version);
        p.writeString(nonce);
    }

    private static void requestHeader(Parcel p, boolean loading) {
        header(p, 1, UUID.randomUUID().toString());
        p.writeLong(SystemClock.elapsedRealtime() + ConfigBinderProtocol.TIMEOUT_MS);
        p.writeInt(loading ? 1 : 0);
        p.writeInt(0);
    }

    private static void entries(Parcel p, String... pairs) {
        p.writeInt(pairs.length / 2);
        for (String value : pairs) p.writeString(value);
    }

    private static void response(Parcel p, String nonce, String status, Map<String, String> values) {
        header(p, 1, nonce);
        p.writeString(status);
        p.writeInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            p.writeString(entry.getKey());
            p.writeString(entry.getValue());
        }
    }

    static ConfigBinderProtocol.Request request(boolean loading, boolean needsSession, Map<String, String> values) {
        return new ConfigBinderProtocol.Request(loading, needsSession, values,
                SystemClock.elapsedRealtime() + ConfigBinderProtocol.TIMEOUT_MS);
    }

    static Map<String, String> snapshot(boolean needsSession) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ConfigWriteContract.REVISION_KEY, "12345678-1234-1234-1234-123456789abc");
        if (needsSession) {
            values.put("__installation_id", "12345678-1234-1234-1234-123456789abc");
            values.put("__generation_start", "1");
            values.put("__generation_limit", "1000000");
        }
        return values;
    }

    private static String repeated(int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, 'x');
        return new String(chars);
    }
}
