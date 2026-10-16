package cc.wechat.observatory;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import cc.wechat.observatory.wechat.AccountSessionAllocator;

/** The shared directory is authoritative. Every reader and writer uses its stable lock file. */
public final class SharedConfigStore {
    static final String MAIN = "observatory.properties";
    static final String NEW = MAIN + ".new";
    static final String BACKUP = MAIN + ".bak";
    static final String INSTALLATION = "__installation_id";
    static final String HIGH_WATERMARK = "__reserved_through";
    static final String REVISION = ModuleConfigStore.REVISION;
    static final int MAX_BYTES = 64 * 1024;
    private static final long BLOCK_SIZE = 1_000_000L;
    private static final long LOCK_TIMEOUT_MS = 15000L;

    private SharedConfigStore() { }

    public static Properties read(Context context) throws IOException {
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            return read(files, deadline());
        }
    }

    public static Properties loadForRuntime(Context context) throws IOException {
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            return loadForRuntime(files, deadline());
        }
    }

    public static Properties saveAndLoad(Context context, Properties values, boolean needsSession) throws IOException {
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            return saveAndLoad(files, values, needsSession, deadline());
        }
    }

    public static ModuleConfigStore.SaveResult save(Context context, Properties values) {
        Properties snapshot = null;
        try {
            snapshot = saveAndLoad(context, values, false);
            return new ModuleConfigStore.SaveResult(true, snapshot.getProperty(REVISION), "");
        } catch (IOException | RuntimeException failure) {
            return new ModuleConfigStore.SaveResult(false, "", "公共配置保存失败，请检查目录授权或配置文件后重试");
        } finally { if (snapshot != null) snapshot.clear(); }
    }

    /** Explicit module-side migration, never a fallback used by the WeChat runtime. */
    public static boolean migratePrivate(Context context) throws IOException {
        if (context == null || !ConfigWriteContract.MODULE_PACKAGE.equals(context.getPackageName())
                || context.getApplicationInfo().uid != android.os.Process.myUid()) {
            throw new IOException("Private configuration migration requires the module process");
        }
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            return migratePrivate(files, () -> ModuleConfigStore.exportStateForSharedStorage(context), new MigrationMarker() {
                @Override public boolean isComplete() throws IOException {
                    return ModuleConfigStore.sharedMigrationComplete(context);
                }
                @Override public void complete() throws IOException {
                    ModuleConfigStore.markSharedMigrationComplete(context);
                }
            }, deadline());
        }
    }

    private static long deadline() { return SystemClock.elapsedRealtime() + LOCK_TIMEOUT_MS; }

    // File/backend overloads exercise the same transaction code without Android storage or Binder.
    static Properties read(SharedConfigFiles files, long deadline) throws IOException {
        synchronized (SharedConfigStore.class) {
            try (Closeable lock = files.lock(deadline)) {
                State state = readState(files);
                try {
                    if (state.changed) writeState(files, state.values);
                    return snapshot(state.values);
                } finally { state.values.clear(); }
            }
        }
    }

    static Properties loadForRuntime(SharedConfigFiles files, long deadline) throws IOException {
        synchronized (SharedConfigStore.class) {
            try (Closeable lock = files.lock(deadline)) {
                State state = readState(files);
                try {
                    AccountSessionAllocator.Range range = reserve(state.values);
                    writeState(files, state.values);
                    return runtimeSnapshot(state.values, range);
                } finally { state.values.clear(); }
            }
        }
    }

    static Properties saveAndLoad(SharedConfigFiles files, Properties input, boolean needsSession,
                                  long deadline) throws IOException {
        Properties business = normalizeBusiness(input);
        try {
            synchronized (SharedConfigStore.class) {
                try (Closeable lock = files.lock(deadline)) {
                    State previous = readState(files);
                    Properties next = new Properties();
                    try {
                        next.putAll(business);
                        next.setProperty(INSTALLATION, previous.values.getProperty(INSTALLATION));
                        next.setProperty(HIGH_WATERMARK, previous.values.getProperty(HIGH_WATERMARK));
                        next.setProperty(REVISION, UUID.randomUUID().toString());
                        AccountSessionAllocator.Range range = needsSession ? reserve(next) : null;
                        writeState(files, next);
                        return needsSession ? runtimeSnapshot(next, range) : snapshot(next);
                    } finally { previous.values.clear(); next.clear(); }
                }
            }
        } finally { business.clear(); }
    }

    interface PrivateSource { Properties read() throws IOException; }
    interface MigrationMarker {
        boolean isComplete() throws IOException;
        void complete() throws IOException;
    }

    static boolean migratePrivate(SharedConfigFiles files, PrivateSource source, MigrationMarker marker,
                                  long deadline) throws IOException {
        synchronized (SharedConfigStore.class) {
            try (Closeable lock = files.lock(deadline)) {
                if (marker.isComplete()) return false;
                recover(files);
                // An existing cleared configuration is just as authoritative as a populated one.
                if (files.exists(MAIN)) {
                    State current = readState(files);
                    try {
                        if (current.changed) writeState(files, current.values);
                        marker.complete();
                    } finally { current.values.clear(); }
                    return false;
                }
                Properties imported = source.read();
                if (imported == null) throw new IOException("Private configuration unavailable");
                try {
                    completeAndValidate(imported);
                    writeState(files, imported);
                    marker.complete();
                    return true;
                } finally { imported.clear(); }
            }
        }
    }

    private static State readState(SharedConfigFiles files) throws IOException {
        recover(files);
        boolean exists = files.exists(MAIN);
        Properties state = exists ? readFile(files, MAIN) : new Properties();
        try {
            boolean changed = completeAndValidate(state);
            return new State(state, !exists || changed);
        } catch (IOException | RuntimeException failure) {
            state.clear();
            throw failure;
        }
    }

    private static void recover(SharedConfigFiles files) throws IOException {
        if (files.exists(MAIN)) {
            // The main-file rename is the commit point. Never rewind a corrupt main
            // to an older reservation, even if a backup is still present.
            Properties committed = readFile(files, MAIN);
            try { completeAndValidate(committed); }
            finally { committed.clear(); }
            if (files.exists(BACKUP)) files.delete(BACKUP);
        } else if (files.exists(BACKUP)) {
            Properties previous = readFile(files, BACKUP);
            try { completeAndValidate(previous); }
            finally { previous.clear(); }
            files.rename(BACKUP, MAIN);
        }
        // A standalone .new was never committed; it cannot publish a session range.
        if (files.exists(NEW)) files.delete(NEW);
    }

    private static boolean completeAndValidate(Properties state) throws IOException {
        Properties before = new Properties();
        before.putAll(state);
        Properties business = new Properties();
        try {
            for (Map.Entry<Object, Object> entry : state.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) throw invalid();
                String key = (String) entry.getKey();
                String value = (String) entry.getValue();
                if (!allowed(key) || value.length() > 4096) throw invalid();
                if (ConfigWriteContract.KEYS.contains(key)) business.setProperty(key, value);
            }
            Properties normalized = normalizeBusiness(business);
            try {
                for (String key : ConfigWriteContract.KEYS) state.remove(key);
                state.putAll(normalized);
            } finally { normalized.clear(); }
            String installation = state.getProperty(INSTALLATION);
            String high = state.getProperty(HIGH_WATERMARK);
            if (installation == null && high == null) {
                state.setProperty(INSTALLATION, UUID.randomUUID().toString());
                state.setProperty(HIGH_WATERMARK, "0");
            } else if (!ConfigWriteContract.validNonce(installation) || high == null || parseHigh(high) < 0) {
                throw invalid();
            }
            String revision = state.getProperty(REVISION);
            if (revision == null) state.setProperty(REVISION, UUID.randomUUID().toString());
            else if (!ConfigWriteContract.validNonce(revision)) throw invalid();
            return !before.equals(state);
        } finally { before.clear(); business.clear(); }
    }

    private static Properties normalizeBusiness(Properties input) throws IOException {
        Properties result = new Properties();
        if (input == null || input.isEmpty()) return result;
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (Map.Entry<Object, Object> entry : input.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)
                        || !ConfigWriteContract.KEYS.contains(entry.getKey())) throw invalid();
                values.put((String) entry.getKey(), (String) entry.getValue());
            }
            Map<String, String> normalized = ConfigWriteContract.normalize(values);
            try { result.putAll(normalized); }
            finally { normalized.clear(); }
            return result;
        } catch (RuntimeException | java.net.MalformedURLException failure) {
            result.clear();
            throw invalid();
        } finally { values.clear(); }
    }

    private static long parseHigh(String value) throws IOException {
        try {
            long number = Long.parseLong(value);
            if (number < 0) throw invalid();
            return number;
        } catch (NumberFormatException invalid) { throw invalid(); }
    }

    private static AccountSessionAllocator.Range reserve(Properties state) throws IOException {
        try {
            AccountSessionAllocator.Range range = AccountSessionAllocator.reserveAfter(
                    parseHigh(state.getProperty(HIGH_WATERMARK)), BLOCK_SIZE);
            state.setProperty(HIGH_WATERMARK, Long.toString(range.lastInclusive));
            return range;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("Account generation reservation unavailable");
        }
    }

    private static Properties snapshot(Properties state) {
        Properties result = new Properties();
        for (String key : ConfigWriteContract.KEYS) {
            String value = state.getProperty(key);
            if (value != null) result.setProperty(key, value);
        }
        result.setProperty(REVISION, state.getProperty(REVISION));
        result.setProperty(INSTALLATION, state.getProperty(INSTALLATION));
        return result;
    }

    private static Properties runtimeSnapshot(Properties state, AccountSessionAllocator.Range range) {
        Properties result = snapshot(state);
        result.setProperty("__generation_start", Long.toString(range.firstInclusive));
        result.setProperty("__generation_limit", Long.toString(range.lastInclusive));
        return result;
    }

    private static boolean allowed(String key) {
        return ConfigWriteContract.KEYS.contains(key) || REVISION.equals(key)
                || INSTALLATION.equals(key) || HIGH_WATERMARK.equals(key);
    }

    private static Properties readFile(SharedConfigFiles files, String name) throws IOException {
        byte[] data = files.read(name, MAX_BYTES);
        try {
            if (data == null || data.length == 0 || data.length > MAX_BYTES) throw invalid();
            Properties strict = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    if (!(key instanceof String) || !(value instanceof String) || !allowed((String) key)
                            || ((String) value).length() > 4096 || containsKey(key)) throw new IllegalArgumentException();
                    return super.put(key, value);
                }
            };
            try {
                strict.load(new ByteArrayInputStream(data));
                Properties result = new Properties();
                result.putAll(strict);
                return result;
            } catch (IllegalArgumentException failure) { throw invalid(); }
            finally { strict.clear(); }
        } finally { if (data != null) Arrays.fill(data, (byte) 0); }
    }

    private static void writeState(SharedConfigFiles files, Properties state) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        state.store(output, "WeChat Observatory shared configuration; remove this directory manually after uninstall");
        byte[] encoded = output.toByteArray();
        try {
            if (encoded.length > MAX_BYTES) throw invalid();
            files.writeSync(NEW, encoded);
            confirm(files, NEW, state);
            if (files.exists(BACKUP)) files.delete(BACKUP);
            if (files.exists(MAIN)) files.rename(MAIN, BACKUP);
            files.rename(NEW, MAIN);
            confirm(files, MAIN, state);
            if (files.exists(BACKUP)) files.delete(BACKUP);
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    private static void confirm(SharedConfigFiles files, String name, Properties expected) throws IOException {
        Properties actual = readFile(files, name);
        try {
            if (!expected.equals(actual)) throw new IOException("Shared configuration commit verification failed");
        } finally { actual.clear(); }
    }

    private static IOException invalid() { return new IOException("Invalid shared configuration file"); }

    private static final class State {
        final Properties values;
        final boolean changed;
        State(Properties values, boolean changed) { this.values = values; this.changed = changed; }
    }
}
