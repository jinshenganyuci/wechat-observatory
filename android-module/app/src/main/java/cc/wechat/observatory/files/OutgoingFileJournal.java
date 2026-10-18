package cc.wechat.observatory.files;

import android.content.Context;
import android.util.AtomicFile;
import android.system.Os;
import android.system.ErrnoException;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Durable at-most-once intent. Unknown native submissions retain their source and tombstone. */
public final class OutgoingFileJournal {
    public static final String DIRECTORY = "observatory-outgoing";
    private static final int MAX_RECORDS = 1024;
    private static final long MAX_STAGED = 8 * FileJob.MAX_BYTES;
    private final File directory;
    interface DirectorySync { void sync(File directory) throws IOException; }
    private final DirectorySync directorySync;

    public OutgoingFileJournal(Context context) throws IOException {
        if (!"com.tencent.mm".equals(context.getPackageName())
                || context.getApplicationInfo().uid != android.os.Process.myUid())
            throw new IOException("File delivery requires the WeChat UID");
        directorySync = OutgoingFileJournal::syncDirectory;
        directory = new File(context.getNoBackupFilesDir().getCanonicalFile(), DIRECTORY);
        check(directory);
    }

    // Actual temporary files exercise the same transaction logic in JVM tests.
    OutgoingFileJournal(File root, DirectorySync sync) throws IOException {
        directorySync = sync;
        directory = new File(root.getCanonicalFile(), DIRECTORY);
        check(directory);
    }

    public static String scope(String gateway, String apiKey, String installation, String owner) throws Exception {
        return FileJob.digest((gateway + "\n" + apiKey + "\n" + installation + "\n" + owner)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String key(String scope, long id) throws Exception {
        return FileJob.digest((scope + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized Record create(String scope, String installation, JSONObject item) throws Exception {
        FileJob job = new FileJob(item);
        String key = key(scope, job.id);
        if (path(key, ".json").exists() || path(key, ".json.bak").exists())
            throw new IOException("File delivery intent already exists");
        ensureDirectory();
        pruneAcknowledged(false);
        if (records().size() >= MAX_RECORDS) throw new IOException("Unconfirmed file journals fill the limit; new file delivery stopped");
        Record record = new Record(key, new JSONObject().put("scope", scope).put("installation", installation)
                .put("item", new JSONObject(item.toString())).put("state", "prepared")
                .put("created", System.currentTimeMillis()).put("message_id", 0)
                .put("native_finished", false).put("phases_ok", false).put("source_released", false));
        write(record);
        return record;
    }

    public synchronized Record find(String scope, long id) throws Exception {
        String key = key(scope, id);
        if (!path(key, ".json").exists() && !path(key, ".json.bak").exists()) return null;
        return read(key);
    }

    public synchronized List<Record> records() throws Exception {
        List<Record> result = new ArrayList<>();
        check(directory);
        if (!directory.exists()) return result;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read file journal");
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (File file : children) {
            check(file);
            String name = file.getName();
            if (name.matches("[0-9a-f]{64}\\.json(?:\\.bak)?")) keys.add(name.substring(0, 64));
        }
        if (keys.size() > MAX_RECORDS) throw new IOException("File journal limit exceeded");
        for (String key : keys) result.add(read(key));
        return result;
    }

    public synchronized Record read(String key) throws Exception {
        File main = path(key, ".json");
        File backup = path(key, ".json.bak");
        check(main); check(backup); check(path(key, ".json.new"));
        // Validate before AtomicFile is allowed to restore an interrupted write.
        JSONObject state = parse(backup.exists() ? backup : main);
        validate(key, state);
        try (InputStream ignored = new AtomicFile(main).openRead()) { }
        return new Record(key, state);
    }

    public synchronized void write(Record record) throws Exception {
        validate(record.key, record.data);
        ensureDirectory();
        byte[] bytes = record.data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new IOException("Oversized file journal");
        File main = path(record.key, ".json");
        check(main); check(path(record.key, ".json.bak")); check(path(record.key, ".json.new"));
        AtomicFile atomic = new AtomicFile(main);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite(); output.write(bytes); output.getFD().sync();
            atomic.finishWrite(output); output = null;
            JSONObject verified = parse(main);
            if (!verified.toString().equals(record.data.toString())) throw new IOException("File journal commit failed");
            directorySync.sync(directory);
        } catch (Exception failure) {
            if (output != null) atomic.failWrite(output);
            throw failure;
        }
    }

    public synchronized void state(Record record, String state, String error) throws Exception {
        record.data.put("state", state).put("error", error == null ? "" : error);
        if ("intent".equals(state)) record.data.put("intent_recorded", true);
        write(record);
    }

    /** A fresh lease may retry a download, but only if no begin intent was ever persisted. */
    public synchronized void prepareLease(Record record, JSONObject item) throws Exception {
        if (record.data.optBoolean("intent_recorded")) throw new IOException("Native begin intent is immutable");
        FileJob previous = new FileJob(record.data.getJSONObject("item")), next = new FileJob(item);
        if (previous.id != next.id || !previous.owner.equals(next.owner) || !previous.recipient.equals(next.recipient)
                || !previous.attachmentId.equals(next.attachmentId) || !previous.sha256.equals(next.sha256)
                || previous.size != next.size || !previous.name.equals(next.name)) throw new IOException("Attachment identity changed");
        release(record);
        record.data.put("item", new JSONObject(item.toString())).put("state", "prepared")
                .put("source_released", false).put("acknowledged", false).put("error", "");
        write(record);
    }

    public synchronized void observe(Record record, long messageId, boolean finished, boolean ok) throws Exception {
        long previous = record.data.optLong("message_id", 0);
        if (messageId > 0 && previous > 0 && previous != messageId) throw new IOException("Native message identity changed");
        if (messageId == previous && !finished) return;
        if (messageId > 0) record.data.put("message_id", messageId);
        if (finished) record.data.put("native_finished", true).put("phases_ok", ok);
        write(record);
    }

    public synchronized JSONObject snapshot(Record record) throws Exception { return new JSONObject(record.data.toString()); }
    public synchronized void acknowledged(Record record) throws Exception {
        record.data.put("acknowledged", true); write(record);
    }

    public synchronized File part(Record record) throws IOException { return path(record.key, ".part"); }
    public synchronized File source(Record record) throws IOException {
        try {
            String name = new FileJob(record.data.getJSONObject("item")).name;
            String extension = name.substring(name.lastIndexOf('.') + 1);
            if (name.lastIndexOf('.') < 0 || !extension.matches("[A-Za-z0-9]{1,16}")) extension = "bin";
            return path(record.key, ".source." + extension);
        } catch (Exception failure) { throw new IOException("Invalid staged file identity", failure); }
    }

    public synchronized void reserveDownload(Record record) throws Exception {
        long total = 0;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect outgoing files");
        for (File file : files) {
            check(file);
            if (file.getName().contains(".source.") || file.getName().endsWith(".part")) total += file.length();
        }
        if (total + new FileJob(record.data.getJSONObject("item")).size > MAX_STAGED)
            throw new IOException("Unconfirmed files fill the private staging limit");
        File part = part(record);
        if (part.exists() && !part.delete()) throw new IOException("Cannot remove interrupted download");
        if (!part.createNewFile()) throw new IOException("Download already exists");
    }

    public synchronized void commitDownload(Record record) throws Exception {
        File source = source(record), part = part(record);
        if (source.exists() || !part.renameTo(source)) throw new IOException("Cannot commit downloaded file");
        directorySync.sync(directory);
    }

    public synchronized void release(Record record) throws Exception {
        File source = source(record), part = part(record);
        if (source.exists() && !source.delete()) throw new IOException("Cannot clean staging file");
        if (part.exists() && !part.delete()) throw new IOException("Cannot clean partial file");
        record.data.put("source_released", true);
        write(record);
    }

    /** Same serial worker calls this: no active download competes with orphan collection. */
    public synchronized void cleanupParts() throws Exception {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect partial files");
        for (File file : files) {
            check(file);
            if (file.getName().matches("[0-9a-f]{64}\\.part") && !file.delete())
                throw new IOException("Cannot clean partial file");
        }
    }

    /** Clearing configuration never destroys the only source of an ambiguous native submission. */
    public synchronized int clearSafeSources() throws Exception {
        int retained = 0;
        for (Record record : records()) {
            String state = record.data.getString("state");
            if ("started".equals(state) || "unknown".equals(state) || "intent".equals(state)) {
                if (source(record).exists()) retained++;
            } else release(record);
        }
        cleanupParts();
        pruneAcknowledged(true);
        return retained;
    }

    private void pruneAcknowledged(boolean all) throws Exception {
        List<Record> records = records();
        if (!all && records.size() < MAX_RECORDS) return;
        for (Record record : records) {
            String state = record.data.getString("state");
            if (!("sent".equals(state) || "failed".equals(state))
                    || !record.data.optBoolean("acknowledged") || !record.data.optBoolean("source_released")
                    || source(record).exists() || part(record).exists()) continue;
            // The server's immutable begin record is still authoritative after local audit pruning.
            for (String suffix : new String[]{".json.bak", ".json.new", ".json"}) {
                File file = path(record.key, suffix);
                if (file.exists() && !file.delete()) throw new IOException("Cannot prune completed file journal");
            }
        }
    }

    private File path(String key, String suffix) throws IOException {
        if (!key.matches("[0-9a-f]{64}")) throw new IOException("Invalid journal key");
        check(directory);
        File file = new File(directory, key + suffix); check(file); return file;
    }
    private void ensureDirectory() throws IOException {
        check(directory);
        if (!directory.exists()) {
            if (!directory.mkdir()) throw new IOException("Cannot create file journal");
            directorySync.sync(directory.getParentFile());
        }
        if (!directory.isDirectory()) throw new IOException("Invalid file journal directory");
    }
    private static void check(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("Symlink in file journal");
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode))
                throw new IOException("Symlink in file journal");
        } catch (ErrnoException missing) {
            if (missing.errno != OsConstants.ENOENT) throw new IOException("Cannot inspect file journal", missing);
        }
    }
    private static JSONObject parse(File file) throws Exception {
        check(file);
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 16384) throw new IOException("Oversized file journal");
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private static void validate(String key, JSONObject state) throws Exception {
        FileJob job = new FileJob(state.getJSONObject("item"));
        if (!key.equals(key(state.getString("scope"), job.id)) || state.getString("installation").isEmpty())
            throw new IOException("Invalid file journal identity");
        if (!java.util.Arrays.asList("prepared", "intent", "started", "unknown", "sent", "failed").contains(state.getString("state")))
            throw new IOException("Invalid file journal state");
    }
    private static void syncDirectory(File directory) throws IOException {
        java.io.FileDescriptor fd = null;
        try { fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_DIRECTORY, 0); Os.fsync(fd); }
        catch (Exception failure) { throw new IOException("Cannot sync file journal", failure); }
        finally { if (fd != null) try { Os.close(fd); } catch (Exception ignored) { } }
    }
    public static final class Record {
        public final String key;
        public final JSONObject data;
        Record(String key, JSONObject data) { this.key = key; this.data = data; }
    }
}
