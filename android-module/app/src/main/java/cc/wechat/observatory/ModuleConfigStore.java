package cc.wechat.observatory;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

import cc.wechat.observatory.wechat.AccountSessionAllocator;

/** The only durable configuration store. Always called with the module's own UID/context. */
public final class ModuleConfigStore {
    public static final String REVISION = "__bridge_write_revision";
    private static final String INSTALLATION = "__installation_id";
    private static final String HIGH_WATERMARK = "__reserved_through";
    private static final long SESSION_BLOCK_SIZE = 1_000_000L;

    private ModuleConfigStore() { }

    public static synchronized Properties read(Context context) throws IOException {
        return publicSnapshot(readState(context));
    }

    /** Commit the whole reservation before exposing any generation to WeChat. */
    public static synchronized Properties loadForRuntime(Context context) throws IOException {
        Properties state = readState(context);
        if (state.getProperty(INSTALLATION, "").isEmpty()) {
            state.setProperty(INSTALLATION, UUID.randomUUID().toString());
        }
        long previous;
        try { previous = Long.parseLong(state.getProperty(HIGH_WATERMARK, "0")); }
        catch (NumberFormatException corrupt) { throw new IOException("Invalid session reservation", corrupt); }
        AccountSessionAllocator.Range range;
        try { range = AccountSessionAllocator.reserveAfter(previous, SESSION_BLOCK_SIZE); }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("Session reservation unavailable", invalid);
        }
        state.setProperty(HIGH_WATERMARK, Long.toString(range.lastInclusive));
        writeState(context, state);
        Properties result = publicSnapshot(state);
        result.setProperty(INSTALLATION, state.getProperty(INSTALLATION));
        result.setProperty("__generation_start", Long.toString(range.firstInclusive));
        result.setProperty("__generation_limit", Long.toString(range.lastInclusive));
        return result;
    }

    public static synchronized SaveResult save(Context context, Properties values) {
        try {
            Properties previous = readState(context);
            Properties state = new Properties();
            for (String key : new String[]{INSTALLATION, HIGH_WATERMARK}) {
                String value = previous.getProperty(key);
                if (value != null) state.setProperty(key, value);
            }
            for (String key : BridgeConfigProvider.CONFIG_KEYS) {
                String value = values == null ? null : values.getProperty(key);
                if (value == null || value.trim().isEmpty()) continue;
                if (value.length() > 4096) return new SaveResult(false, "", "配置值过长");
                state.setProperty(key, value.trim());
            }
            String revision = UUID.randomUUID().toString();
            state.setProperty(REVISION, revision);
            writeState(context, state);
            return new SaveResult(true, revision, "");
        } catch (IOException | RuntimeException failure) {
            return new SaveResult(false, "", "无法保存配置，请检查存储空间后重试");
        }
    }

    private static Properties publicSnapshot(Properties state) {
        Properties copy = new Properties();
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            String value = state.getProperty(key);
            if (value != null) copy.setProperty(key, value);
        }
        String revision = state.getProperty(REVISION);
        if (revision != null) copy.setProperty(REVISION, revision);
        return copy;
    }

    private static AtomicFile file(Context context) throws IOException {
        if (!"cc.wechat.observatory".equals(context.getPackageName())) {
            throw new IOException("Configuration requires the module context");
        }
        File directory = context.getNoBackupFilesDir();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Configuration directory unavailable");
        }
        return new AtomicFile(new File(directory, "observatory.properties"));
    }

    private static Properties readState(Context context) throws IOException {
        AtomicFile file = file(context);
        Properties state = new Properties();
        // openRead also restores AtomicFile's backup after an interrupted write.
        try (InputStream input = file.openRead()) { state.load(input); }
        catch (java.io.FileNotFoundException missing) {
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) throw missing;
            // One-time migration precedes deletion of legacy copies. Never
            // import these sources again once the private store exists.
            java.util.Map<String, ?> legacy = context.getSharedPreferences("bridge_config", Context.MODE_PRIVATE).getAll();
            for (String key : BridgeConfigProvider.CONFIG_KEYS) {
                Object value = legacy.get(key);
                if (value instanceof String) state.setProperty(key, (String) value);
            }
            state.setProperty(REVISION, UUID.randomUUID().toString());
            writeState(context, state);
        }
        return state;
    }

    private static void writeState(Context context, Properties state) throws IOException {
        AtomicFile file = file(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            state.store(output, "WeChat Observatory private configuration");
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            // Android AtomicFile logs some rename errors instead of throwing.
            // Do not expose a session range unless its durable state reads back.
            Properties confirmed = new Properties();
            try (InputStream input = file.openRead()) { confirmed.load(input); }
            if (!state.equals(confirmed)) throw new IOException("Configuration commit verification failed");
        } catch (IOException | RuntimeException failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }

    public static final class SaveResult {
        public final boolean success;
        public final String revision;
        public final String error;
        SaveResult(boolean success, String revision, String error) {
            this.success = success;
            this.revision = revision;
            this.error = error;
        }
    }
}
