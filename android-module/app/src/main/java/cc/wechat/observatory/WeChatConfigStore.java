package cc.wechat.observatory;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import cc.wechat.observatory.wechat.AccountSessionAllocator;

/** Configuration owned by the injected WeChat process, separate from WeChat's business files. */
public final class WeChatConfigStore {
    static final String DIRECTORY = "observatory";
    static final String MAIN = "observatory.properties";
    static final String INSTALLATION = "__installation_id";
    static final String HIGH_WATERMARK = "__reserved_through";
    static final String REVISION = ConfigWriteContract.REVISION_KEY;
    static final int MAX_BYTES = 64 * 1024;
    private static final long BLOCK_SIZE = 1_000_000L;
    private static final String[] OWNED_NAMES = {MAIN, MAIN + ".bak", MAIN + ".new"};

    private WeChatConfigStore() { }

    /** Missing configuration is a read-only empty state; no defaults are written to disk. */
    public static synchronized Properties read(Context context) throws IOException {
        Location location = locate(context, false);
        Properties state = readState(location);
        try { return snapshot(state); }
        finally { state.clear(); }
    }

    /** Persist a fresh generation range before exposing it to the runtime. */
    public static synchronized Properties loadForRuntime(Context context) throws IOException {
        Location location = locate(context, false);
        Properties state = readState(location);
        try {
            if (state.isEmpty()) return new Properties();
            AccountSessionAllocator.Range range = reserve(state);
            writeState(location, state);
            return runtimeSnapshot(state, range);
        } finally { state.clear(); }
    }

    public static synchronized Properties saveAndLoad(Context context, Properties input,
                                                       boolean needsSession) throws IOException {
        // Check the caller even for an empty clear or invalid input.
        Location existing = locate(context, false);
        Properties business = normalizeBusiness(input);
        try {
            if (business.isEmpty()) {
                delete(context);
                return new Properties();
            }
            // A corrupt committed file must never be replaced by a new save.
            Properties previous = readState(existing);
            Properties next = new Properties();
            try {
                next.putAll(business);
                next.setProperty(INSTALLATION, previous.getProperty(INSTALLATION, UUID.randomUUID().toString()));
                next.setProperty(HIGH_WATERMARK, previous.getProperty(HIGH_WATERMARK, "0"));
                next.setProperty(REVISION, UUID.randomUUID().toString());
                AccountSessionAllocator.Range range = needsSession ? reserve(next) : null;
                Location destination = existing == null ? locate(context, true) : existing;
                writeState(destination, next);
                return needsSession ? runtimeSnapshot(next, range) : snapshot(next);
            } finally { previous.clear(); next.clear(); }
        } finally { business.clear(); }
    }

    /** Delete only our three AtomicFile paths and their empty containing directory. */
    public static synchronized void delete(Context context) throws IOException {
        Location location = locate(context, false);
        if (location == null || stat(location.directory) == null) return;
        location.check();
        String[] children = location.directory.list();
        if (children == null) throw new IOException("Cannot inspect configuration directory");
        for (String child : children) {
            if (!Arrays.asList(OWNED_NAMES).contains(child)) {
                throw new IOException("Configuration directory contains an unexpected file");
            }
        }
        // Remove recovery candidates before the committed file. An interrupted
        // clear must not leave a backup that a later read could restore.
        for (String name : new String[]{MAIN + ".bak", MAIN + ".new", MAIN}) {
            File file = new File(location.directory, name);
            requireType(file, false, true);
            if (stat(file) != null) remove(file);
        }
        requireType(location.directory, true, false);
        remove(location.directory);
    }

    private static Location locate(Context context, boolean create) throws IOException {
        if (context == null || !"com.tencent.mm".equals(context.getPackageName())) {
            throw new IOException("Configuration requires the WeChat context");
        }
        ApplicationInfo info = context.getApplicationInfo();
        if (info == null || info.uid != android.os.Process.myUid() || info.dataDir == null) {
            throw new IOException("Configuration requires the WeChat process UID");
        }
        File data = new File(info.dataDir).getCanonicalFile();
        requireType(data, true, false);
        File expectedRoot = new File(data, "no_backup");
        // ContextImpl.getNoBackupFilesDir() creates its directory. Do not call it
        // for an empty read/delete when even that framework directory is absent.
        if (!create && stat(expectedRoot) == null) return null;
        requireType(expectedRoot, true, true);
        File root = context.getNoBackupFilesDir();
        if (root == null || !expectedRoot.equals(root.getCanonicalFile())) {
            throw new IOException("Unexpected WeChat private configuration root");
        }
        requireType(expectedRoot, true, false);
        Location location = new Location(new File(expectedRoot, DIRECTORY));
        location.check();
        if (create && stat(location.directory) == null) {
            if (!location.directory.mkdir()) throw new IOException("Cannot create configuration directory");
            location.check();
        }
        return location;
    }

    private static Properties readState(Location location) throws IOException {
        if (location == null) return new Properties();
        location.check();
        File main = location.main;
        File backup = new File(main + ".bak");
        boolean hasBackup = stat(backup) != null;
        if (!hasBackup && stat(main) == null) return new Properties();
        // A .bak is AtomicFile's uncommitted-write marker on older Android.
        // Validate it before openRead is allowed to replace the interrupted main.
        Properties validated = parse(hasBackup ? backup : main);
        try { validate(validated); }
        finally { validated.clear(); }
        AtomicFile atomic = new AtomicFile(main);
        try (FileInputStream ignored = atomic.openRead()) { }
        location.check();
        Properties state = parse(main);
        try {
            boolean changed = validate(state);
            if (changed) writeState(location, state);
            return state;
        } catch (IOException | RuntimeException failure) {
            state.clear();
            throw failure;
        }
    }

    private static void writeState(Location location, Properties state) throws IOException {
        if (location == null) throw new IOException("Configuration directory unavailable");
        location.check();
        if (stat(location.directory) == null) {
            if (!location.directory.mkdir()) throw new IOException("Cannot create configuration directory");
            location.check();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        state.store(bytes, "WeChat Observatory private configuration");
        byte[] encoded = bytes.toByteArray();
        AtomicFile atomic = new AtomicFile(location.main);
        FileOutputStream output = null;
        try {
            if (encoded.length > MAX_BYTES) throw invalid();
            output = atomic.startWrite();
            output.write(encoded);
            output.flush();
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            location.check();
            // AtomicFile logs some rename/delete errors instead of throwing.
            // A leftover backup means an older Android commit was not complete.
            if (stat(new File(location.main + ".bak")) != null
                    || stat(new File(location.main + ".new")) != null) {
                throw new IOException("Configuration commit did not finish");
            }
            Properties confirmed = parse(location.main);
            try {
                if (!state.equals(confirmed)) throw new IOException("Configuration commit verification failed");
            } finally { confirmed.clear(); }
        } catch (IOException | RuntimeException failure) {
            if (output != null) atomic.failWrite(output);
            throw failure;
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    private static Properties parse(File file) throws IOException {
        requireType(file, false, false);
        byte[] encoded;
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw invalid();
                bytes.write(buffer, 0, count);
            }
            encoded = bytes.toByteArray();
        }
        Properties strict = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (!(key instanceof String) || !(value instanceof String) || !allowed((String) key)
                        || ((String) value).length() > 4096 || containsKey(key)) {
                    throw new IllegalArgumentException("Invalid configuration entry");
                }
                return super.put(key, value);
            }
        };
        try {
            if (encoded.length == 0) throw invalid();
            strict.load(new ByteArrayInputStream(encoded));
            Properties state = new Properties();
            state.putAll(strict);
            return state;
        } catch (IllegalArgumentException failure) { throw invalid(); }
        finally { Arrays.fill(encoded, (byte) 0); strict.clear(); }
    }

    private static boolean validate(Properties state) throws IOException {
        Properties before = new Properties();
        before.putAll(state);
        Properties business = new Properties();
        try {
            for (String key : ConfigWriteContract.KEYS) {
                String value = state.getProperty(key);
                if (value != null) business.setProperty(key, value);
            }
            Properties normalized = normalizeBusiness(business);
            try {
                if (normalized.isEmpty()) throw invalid();
                for (String key : ConfigWriteContract.KEYS) state.remove(key);
                state.putAll(normalized);
            } finally { normalized.clear(); }
            String installation = state.getProperty(INSTALLATION);
            String high = state.getProperty(HIGH_WATERMARK);
            // This private path is created only by this store. Missing metadata
            // is corruption, not a migration or a new installation.
            if (!ConfigWriteContract.validNonce(installation) || high == null || parseHigh(high) < 0) {
                throw invalid();
            }
            String revision = state.getProperty(REVISION);
            if (!ConfigWriteContract.validNonce(revision)) throw invalid();
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
                String value = (String) entry.getValue();
                if (value.length() > 4096) throw invalid();
                if (!value.trim().isEmpty()) values.put((String) entry.getKey(), value);
            }
            if (values.isEmpty()) return result;
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
        if (state.isEmpty()) return result;
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

    private static StructStat stat(File file) throws IOException {
        try { return Os.lstat(file.getAbsolutePath()); }
        catch (ErrnoException failure) {
            if (failure.errno == OsConstants.ENOENT) return null;
            throw new IOException("Cannot inspect private configuration path", failure);
        }
    }

    private static void requireType(File file, boolean directory, boolean missingAllowed) throws IOException {
        StructStat status = stat(file);
        if (status == null) {
            if (missingAllowed) return;
            throw new IOException("Private configuration path is missing");
        }
        if (directory ? !OsConstants.S_ISDIR(status.st_mode) : !OsConstants.S_ISREG(status.st_mode)) {
            throw new IOException("Unexpected private configuration path type");
        }
    }

    private static void remove(File file) throws IOException {
        try { Os.remove(file.getAbsolutePath()); }
        catch (ErrnoException failure) { throw new IOException("Cannot delete private configuration path", failure); }
        if (stat(file) != null) throw new IOException("Private configuration deletion was not confirmed");
    }

    private static IOException invalid() { return new IOException("Invalid WeChat configuration file"); }

    private static final class Location {
        final File directory;
        final File main;
        Location(File directory) { this.directory = directory; this.main = new File(directory, MAIN); }
        void check() throws IOException {
            requireType(directory.getParentFile(), true, false);
            requireType(directory, true, true);
            if (stat(directory) == null) return;
            for (String name : OWNED_NAMES) requireType(new File(directory, name), false, true);
        }
    }
}
