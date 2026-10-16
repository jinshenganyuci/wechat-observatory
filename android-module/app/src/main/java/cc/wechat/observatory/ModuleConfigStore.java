package cc.wechat.observatory;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import cc.wechat.observatory.wechat.AccountSessionAllocator;

/** The only durable configuration store. Always called with the module's own UID/context. */
public final class ModuleConfigStore {
    public static final String REVISION = "__bridge_write_revision";
    private static final String INSTALLATION = "__installation_id";
    private static final String HIGH_WATERMARK = "__reserved_through";
    private static final long SESSION_BLOCK_SIZE = 1_000_000L;
    private static final int MAX_LEGACY_XML_BYTES = 256 * 1024;

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
        if (!"cc.wechat.observatory".equals(context.getPackageName())
                || context.getApplicationInfo().uid != android.os.Process.myUid()) {
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
        try (InputStream input = file.openRead()) { loadProperties(input, state); }
        catch (java.io.FileNotFoundException missing) {
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) throw missing;
            // One-time migration precedes deletion of legacy copies. Never
            // import these sources again once the private store exists.
            try {
                Map<String, ?> legacy = context.getSharedPreferences("bridge_config", Context.MODE_PRIVATE).getAll();
                state.putAll(legacySnapshot(legacy, () -> readPrivateLegacy(context)));
            } catch (RuntimeException invalid) {
                throw new IOException("Legacy configuration unavailable", invalid);
            }
            state.setProperty(REVISION, UUID.randomUUID().toString());
            writeState(context, state);
        }
        return state;
    }

    interface LegacySource {
        Map<String, ?> read() throws IOException;
    }

    static Properties legacySnapshot(Map<String, ?> current, LegacySource privateSource) throws IOException {
        if (current == null) throw new IOException("Legacy configuration unavailable");
        // Any current snapshot is authoritative, including a revision-only clear.
        Map<String, ?> selected = current.isEmpty() ? privateSource.read() : current;
        Properties migrated = new Properties();
        for (String key : BridgeConfigProvider.CONFIG_KEYS) {
            Object value = selected.get(key);
            if (value instanceof String) migrated.setProperty(key, (String) value);
        }
        return migrated;
    }

    private static Map<String, ?> readPrivateLegacy(Context context) throws IOException {
        File data = new File(context.getApplicationInfo().dataDir).getCanonicalFile();
        File xml = new File(data, "shared_prefs/bridge_config.xml");
        File backup = new File(xml + ".bak");
        // Android's legacy SharedPreferences restores the backup first after an
        // interrupted write. Read it without renaming or changing either source.
        File source = backup.exists() ? backup : xml;
        if (!source.getCanonicalPath().equals(source.getAbsolutePath())) {
            throw new IOException("Legacy configuration path changed");
        }
        if (!source.exists()) return new HashMap<>();
        byte[] contents;
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_LEGACY_XML_BYTES) {
                    throw new IOException("Legacy configuration exceeds size limit");
                }
                bytes.write(buffer, 0, count);
            }
            contents = bytes.toByteArray();
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(contents), "UTF-8");
            return parseLegacyXml(parser);
        } catch (XmlPullParserException invalid) {
            throw new IOException("Invalid legacy configuration XML", invalid);
        } finally {
            Arrays.fill(contents, (byte) 0);
        }
    }

    /** Accept only the string map emitted by the old module; never expand a DTD. */
    static Map<String, String> parseLegacyXml(XmlPullParser parser) throws IOException {
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); }
        catch (XmlPullParserException unsupported) {
            // Older kXML does not expose this optional feature and already has
            // it disabled. An implementation that keeps it enabled is unsafe.
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                throw new IOException("Cannot disable legacy XML declarations", unsupported);
            }
        }
        Map<String, String> result = new HashMap<>();
        Set<String> names = new HashSet<>();
        boolean opened = false;
        boolean closed = false;
        String key = null;
        StringBuilder value = new StringBuilder();
        try {
            while (true) {
                int token = parser.nextToken();
                if (token == XmlPullParser.END_DOCUMENT) break;
                if (token == XmlPullParser.DOCDECL) throw new IOException("Legacy XML declarations are not allowed");
                if (token == XmlPullParser.START_TAG) {
                    if (!opened && parser.getDepth() == 1 && "map".equals(parser.getName())
                            && parser.getAttributeCount() == 0) {
                        opened = true;
                    } else if (opened && !closed && parser.getDepth() == 2
                            && "string".equals(parser.getName()) && parser.getAttributeCount() == 1
                            && "name".equals(parser.getAttributeName(0))) {
                        key = parser.getAttributeValue(0);
                        if (key == null || key.length() > 128 || !names.add(key)) {
                            throw new IOException("Invalid legacy preference name");
                        }
                        value.setLength(0);
                    } else throw new IOException("Unsupported legacy preference structure");
                } else if (token == XmlPullParser.END_TAG) {
                    if (parser.getDepth() == 2 && key != null && "string".equals(parser.getName())) {
                        result.put(key, value.toString());
                        key = null;
                        value.setLength(0);
                    } else if (parser.getDepth() == 1 && opened && "map".equals(parser.getName())) {
                        closed = true;
                    } else throw new IOException("Invalid legacy preference structure");
                } else if (token == XmlPullParser.TEXT || token == XmlPullParser.CDSECT
                        || token == XmlPullParser.ENTITY_REF || token == XmlPullParser.IGNORABLE_WHITESPACE) {
                    String text = parser.getText();
                    if (text == null) throw new IOException("Unresolved legacy XML entity");
                    if (key == null) {
                        if (!text.trim().isEmpty()) throw new IOException("Unexpected legacy XML text");
                    } else {
                        if (value.length() + text.length() > 16384) throw new IOException("Legacy preference value too long");
                        value.append(text);
                    }
                }
            }
        } catch (XmlPullParserException invalid) {
            throw new IOException("Invalid legacy configuration XML", invalid);
        }
        if (!opened || !closed || key != null) throw new IOException("Incomplete legacy configuration XML");
        return result;
    }

    static void loadProperties(InputStream input, Properties destination) throws IOException {
        try { destination.load(input); }
        catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid private configuration encoding", invalid);
        }
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
            try (InputStream input = file.openRead()) { loadProperties(input, confirmed); }
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
