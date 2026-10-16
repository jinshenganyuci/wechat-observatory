package cc.wechat.observatory;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Small, versioned string-only RPC. No application class is unmarshalled across UIDs. */
public final class ConfigBinderProtocol {
    public static final String SERVICE_CLASS = "cc.wechat.observatory.ConfigService";
    public static final String OK = ConfigWriteContract.OK;
    public static final String DENIED = ConfigWriteContract.DENIED;
    public static final String INVALID = ConfigWriteContract.INVALID;
    public static final String FAILED = ConfigWriteContract.FAILED;
    public static final String EXPIRED = "expired";
    public static final long TIMEOUT_MS = 15000L;
    static final String DESCRIPTOR = "cc.wechat.observatory.config.Binder";
    static final int VERSION = 1;
    static final int TRANSACTION_CONFIG = IBinder.FIRST_CALL_TRANSACTION;
    static final int MAX_PARCEL_BYTES = 64 * 1024;
    static final int MAX_VALUE_CHARS = 4096;
    static final int MAX_TOTAL_CHARS = 16384;

    private ConfigBinderProtocol() { }

    public static final class Request {
        public final boolean loading;
        public final boolean needsSession;
        public final Map<String, String> values;
        public final String nonce;
        public final long deadlineElapsedMs;

        public Request(boolean loading, boolean needsSession, Map<String, String> values, long deadlineElapsedMs) {
            this(loading, needsSession, values, deadlineElapsedMs, UUID.randomUUID().toString());
        }

        private Request(boolean loading, boolean needsSession, Map<String, String> values,
                        long deadlineElapsedMs, String nonce) {
            validateMap(values, false, false);
            if ((loading && !values.isEmpty()) || deadlineElapsedMs <= 0 || !ConfigWriteContract.validNonce(nonce)) {
                throw invalid();
            }
            this.loading = loading;
            this.needsSession = needsSession;
            this.values = new LinkedHashMap<>(values);
            this.nonce = nonce;
            this.deadlineElapsedMs = deadlineElapsedMs;
        }

        public void clear() { values.clear(); }
    }

    public static final class Response {
        public final String status;
        public final Map<String, String> values;

        private Response(String status, Map<String, String> values) {
            this.status = status;
            this.values = new LinkedHashMap<>(values);
        }

        public void clear() { values.clear(); }
    }

    /** Must run off the UI thread; cancellation cannot retract an already-started write. */
    public static Response transact(IBinder binder, Request request) throws RemoteException {
        if (binder == null || request == null) throw invalid();
        if (SystemClock.elapsedRealtime() >= request.deadlineElapsedMs) return failure(EXPIRED);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeRequest(data, request);
            if (!binder.transact(TRANSACTION_CONFIG, data, reply, 0)) {
                throw new RemoteException("Unsupported configuration service");
            }
            return readResponse(reply, request);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void writeRequest(Parcel parcel, Request request) {
        validateMap(request.values, false, false);
        if (request.loading && !request.values.isEmpty()) throw invalid();
        writeHeader(parcel, request.nonce);
        parcel.writeLong(request.deadlineElapsedMs);
        parcel.writeInt(request.loading ? 1 : 0);
        parcel.writeInt(request.needsSession ? 1 : 0);
        writeMap(parcel, request.values);
        checkSize(parcel);
    }

    static Request readRequest(Parcel parcel) {
        checkSize(parcel);
        String nonce = readHeader(parcel);
        if (!ConfigWriteContract.validNonce(nonce) || parcel.dataAvail() < 16) throw invalid();
        long deadline = parcel.readLong();
        boolean loading = readBoolean(parcel);
        boolean needsSession = readBoolean(parcel);
        Map<String, String> values = readMap(parcel, false, false);
        try {
            if (parcel.dataAvail() != 0) throw invalid();
            return new Request(loading, needsSession, values, deadline, nonce);
        } finally {
            values.clear();
        }
    }

    static Response success(Map<String, String> values, boolean needsSession) {
        validateMap(values, true, needsSession);
        validateSuccess(values, needsSession);
        return new Response(OK, values);
    }

    static Response failure(String status) {
        if (OK.equals(status) || !knownStatus(status)) throw invalid();
        return new Response(status, Collections.emptyMap());
    }

    static void writeResponse(Parcel parcel, String nonce, Response response) {
        if (!knownStatus(response.status) || (!OK.equals(response.status) && !response.values.isEmpty())) throw invalid();
        writeHeader(parcel, nonce == null ? "" : nonce);
        parcel.writeString(response.status);
        writeMap(parcel, response.values);
        checkSize(parcel);
    }

    static Response readResponse(Parcel parcel, Request request) {
        checkSize(parcel);
        String nonce = readHeader(parcel);
        String status = parcel.readString();
        if (!knownStatus(status)) throw invalid();
        // Authorization fails before parsing a request, so that response has no nonce.
        if (!request.nonce.equals(nonce)
                && !(nonce != null && nonce.isEmpty() && (DENIED.equals(status) || INVALID.equals(status)))) throw invalid();
        Map<String, String> values = readMap(parcel, true, request.needsSession);
        try {
            if (parcel.dataAvail() != 0) throw invalid();
            if (OK.equals(status)) return success(values, request.needsSession);
            if (!values.isEmpty()) throw invalid();
            return failure(status);
        } finally {
            values.clear();
        }
    }

    private static void writeHeader(Parcel parcel, String nonce) {
        parcel.writeString(DESCRIPTOR);
        parcel.writeInt(VERSION);
        parcel.writeString(nonce);
    }

    private static String readHeader(Parcel parcel) {
        if (parcel.dataAvail() < 12 || !DESCRIPTOR.equals(parcel.readString()) || parcel.readInt() != VERSION) throw invalid();
        String nonce = parcel.readString();
        if (nonce == null || nonce.length() > 36) throw invalid();
        return nonce;
    }

    private static boolean readBoolean(Parcel parcel) {
        if (parcel.dataAvail() < 4) throw invalid();
        int value = parcel.readInt();
        if (value != 0 && value != 1) throw invalid();
        return value == 1;
    }

    private static void writeMap(Parcel parcel, Map<String, String> values) {
        parcel.writeInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            parcel.writeString(entry.getKey());
            parcel.writeString(entry.getValue());
        }
    }

    private static Map<String, String> readMap(Parcel parcel, boolean response, boolean needsSession) {
        if (parcel.dataAvail() < 4) throw invalid();
        int count = parcel.readInt();
        int limit = ConfigWriteContract.KEYS.size() + (response ? ConfigWriteContract.RUNTIME_KEYS.size() : 0);
        if (count < 0 || count > limit) throw invalid();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            int total = 0;
            for (int i = 0; i < count; i++) {
                if (parcel.dataAvail() < 8) throw invalid();
                String key = parcel.readString();
                String value = parcel.readString();
                if (!allowedKey(key, response, needsSession) || value == null
                        || value.length() > MAX_VALUE_CHARS || values.containsKey(key)) throw invalid();
                total += value.length();
                if (total > MAX_TOTAL_CHARS + (response ? 1024 : 0)) throw invalid();
                values.put(key, value);
            }
            return values;
        } catch (RuntimeException invalid) {
            values.clear();
            throw invalid;
        }
    }

    private static void validateMap(Map<String, String> values, boolean response, boolean needsSession) {
        if (values == null || values.size() > ConfigWriteContract.KEYS.size()
                + (response ? ConfigWriteContract.RUNTIME_KEYS.size() : 0)) throw invalid();
        int total = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!allowedKey(entry.getKey(), response, needsSession) || entry.getValue() == null
                    || entry.getValue().length() > MAX_VALUE_CHARS) throw invalid();
            total += entry.getValue().length();
            if (total > MAX_TOTAL_CHARS + (response ? 1024 : 0)) throw invalid();
        }
    }

    private static boolean allowedKey(String key, boolean response, boolean needsSession) {
        return key != null && (ConfigWriteContract.KEYS.contains(key)
                || response && ConfigWriteContract.REVISION_KEY.equals(key)
                || response && needsSession && ConfigWriteContract.RUNTIME_KEYS.contains(key));
    }

    private static void validateSuccess(Map<String, String> values, boolean needsSession) {
        if (!ConfigWriteContract.validNonce(values.get(ConfigWriteContract.REVISION_KEY))) throw invalid();
        if (!needsSession) return;
        String instance = values.get("__installation_id");
        if (instance == null || !instance.matches("[A-Za-z0-9_-]{16,64}")) throw invalid();
        try {
            long first = Long.parseLong(values.get("__generation_start"));
            long last = Long.parseLong(values.get("__generation_limit"));
            if (first <= 0 || last < first) throw invalid();
        } catch (NumberFormatException malformed) {
            throw invalid();
        }
    }

    private static boolean knownStatus(String status) {
        return OK.equals(status) || DENIED.equals(status) || INVALID.equals(status)
                || FAILED.equals(status) || EXPIRED.equals(status);
    }

    private static void checkSize(Parcel parcel) {
        if (parcel.dataSize() > MAX_PARCEL_BYTES || parcel.dataSize() <= 0) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid configuration protocol payload");
    }
}
