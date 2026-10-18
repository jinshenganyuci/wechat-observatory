package cc.wechat.observatory.lab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Isolated, manually triggered experiment; never include in the production build. */
public final class FileSendLab {
    public static final String ACTION = "cc.wechat.observatory.FILE_SEND_LAB";
    private static final String TAG = "ObservatoryFileLab";
    private static final String DIRECTORY = "observatory-file-probe";
    private static final long OBSERVE_MS = 120_000;
    private static FileSendLab installed;

    public interface Host {
        /** Verify the pinned, registered phone-test account/configuration on the main thread. */
        void validate() throws Exception;
        /** Revalidate immediately before native submission; recipient MUST be filehelper. */
        void submit(File source, String displayName) throws Exception;
        /** Revalidate, then return only message_id, status and source_released. No message bodies. */
        JSONObject inspect(String displayName) throws Exception;
    }

    private final Context context;
    private final Host host;
    private volatile boolean finished;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "observatory-file-lab");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private FileSendLab(Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    public static synchronized void install(Context context, Host host) {
        if (installed != null) return;
        if (Build.VERSION.SDK_INT != 36 || Process.myUid() / 100000 != 999
                || !"com.tencent.mm".equals(context.getPackageName())
                || context.getApplicationInfo().uid != Process.myUid()) {
            throw new SecurityException("unsupported laboratory process");
        }
        Context app = context.getApplicationContext();
        FileSendLab lab = new FileSendLab(app == null ? context : app, host);
        lab.context.registerReceiver(lab.receiver, new IntentFilter(ACTION),
                "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        installed = lab;
        event("-", "receiver_ready", "");
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            try {
                if (!ACTION.equals(intent.getAction())) return;
                Bundle extras = intent.getExtras();
                String operation = intent.getStringExtra("op");
                if ("finish".equals(operation) && extras != null && extras.size() == 1) {
                    worker.execute(() -> run("finish", "-"));
                    return;
                }
                if (extras == null || extras.size() != 2 || !extras.containsKey("op")
                        || !extras.containsKey("case")) {
                    throw new IllegalArgumentException("unsupported request");
                }
                String testCase = intent.getStringExtra("case");
                extension(testCase);
                if (!"send".equals(operation) && !"status".equals(operation)
                        && !"cleanup".equals(operation)) {
                    throw new IllegalArgumentException("unsupported operation");
                }
                worker.execute(() -> run(operation, testCase));
            } catch (Exception failure) {
                event("-", "request_rejected", "error=" + failure.getClass().getSimpleName());
            }
        }
    };

    private void run(String operation, String testCase) {
        try {
            if (finished && !"finish".equals(operation)) {
                event(testCase, "lab_finished", "native_retry=forbidden");
                return;
            }
            host.validate();
            File directory = directory();
            if ("finish".equals(operation)) finish(directory);
            else if ("send".equals(operation)) send(directory, testCase);
            else inspect(directory, testCase, "cleanup".equals(operation));
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // A native call may have started. An exception never authorizes a retry or deletion.
            event(testCase, "unknown", "error=" + failure.getClass().getSimpleName()
                    + " cause=" + (failure.getCause() == null ? "none" : failure.getCause().getClass().getSimpleName()));
        }
    }

    private void send(File directory, String testCase) throws Exception {
        File journal = new File(directory, testCase + ".journal");
        // Exclusive creation is also the cross-process at-most-one claim. Never remove journals.
        if (!journal.createNewFile()) {
            event(testCase, "already_started", "native_retry=forbidden");
            return;
        }
        String name = "observatory-lab-" + testCase + "-" + UUID.randomUUID()
                + extension(testCase);
        JSONObject state = new JSONObject().put("case", testCase).put("file", name);
        writeSync(journal, state.toString().getBytes(StandardCharsets.UTF_8));
        syncDirectory(directory);
        File source = ownedSource(directory, testCase, name);
        byte[] bytes = testBytes(testCase);
        if (!source.createNewFile()) throw new IOException("source already exists");
        writeSync(source, bytes);
        event(testCase, "prepared", "bytes=" + bytes.length + " sha256=" + sha256(bytes));
        host.validate();
        long started = SystemClock.elapsedRealtime();
        event(testCase, "submit_begin", "");
        host.submit(source, name);
        event(testCase, "scheduled", "elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
        String previous = "";
        while (SystemClock.elapsedRealtime() - started < OBSERVE_MS) {
            host.validate();
            JSONObject result = host.inspect(name);
            String status = status(result);
            String evidence = evidence(result);
            if (!evidence.equals(previous)) {
                event(testCase, "observed", evidence + " elapsed_ms="
                        + (SystemClock.elapsedRealtime() - started));
                previous = evidence;
            }
            if ("sent".equals(status) || "failed".equals(status)) return;
            Thread.sleep(1000);
        }
        event(testCase, "observation_timeout", "native_retry=forbidden source=retained");
    }

    private void inspect(File directory, String testCase, boolean cleanup) throws Exception {
        File journal = new File(directory, testCase + ".journal");
        if (!journal.exists()) {
            event(testCase, "not_started", "");
            return;
        }
        JSONObject state = readJournal(journal);
        if (!testCase.equals(state.getString("case"))) throw new IOException("invalid journal");
        File source = ownedSource(directory, testCase, state.getString("file"));
        JSONObject result = host.inspect(source.getName());
        event(testCase, "status", evidence(result));
        if (!cleanup) return;
        String status = status(result);
        if (!("sent".equals(status) || "failed".equals(status))
                || !Boolean.TRUE.equals(result.opt("source_released"))) {
            event(testCase, "cleanup_refused", "source=retained evidence=insufficient");
            return;
        }
        // This proof must be independent of message status. Never delete native-owned copies.
        host.validate();
        if (source.exists() && !source.delete()) throw new IOException("cleanup failed");
        state.put("cleaned", true);
        writeSync(journal, state.toString().getBytes(StandardCharsets.UTF_8));
        syncDirectory(directory);
        event(testCase, "cleanup_complete", "journal=retained");
    }

    private void finish(File directory) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("cannot inspect laboratory directory");
        for (File file : files) {
            String testCase = file.getName().replaceFirst("\\.journal$", "");
            extension(testCase);
            if (!file.getName().equals(testCase + ".journal") || !file.isFile()
                    || !file.equals(file.getCanonicalFile())) throw new IOException("unrecognized file");
            JSONObject state = readJournal(file);
            if (!testCase.equals(state.getString("case"))
                    || !Boolean.TRUE.equals(state.opt("cleaned"))
                    || ownedSource(directory, testCase, state.getString("file")).exists()) {
                throw new IOException("cleanup not confirmed");
            }
        }
        finished = true;
        for (File file : files) if (!file.delete()) throw new IOException("journal cleanup failed");
        if (!directory.delete()) throw new IOException("directory cleanup failed");
        syncDirectory(directory.getParentFile());
        event("-", "finish_complete", "lab_staging=removed native_retry=forbidden");
    }

    private static JSONObject readJournal(File journal) throws Exception {
        if (!journal.equals(journal.getCanonicalFile())) throw new IOException("invalid journal path");
        try (FileInputStream input = new FileInputStream(journal)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 4096) throw new IOException("invalid journal");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private File directory() throws IOException {
        File parent = context.getNoBackupFilesDir().getCanonicalFile();
        File directory = new File(parent, DIRECTORY);
        if ((!directory.exists() && !directory.mkdir()) || !directory.isDirectory()
                || !directory.equals(directory.getCanonicalFile())) {
            throw new IOException("invalid laboratory directory");
        }
        syncDirectory(parent);
        return directory;
    }

    private static File ownedSource(File directory, String testCase, String name) throws IOException {
        String prefix = "observatory-lab-" + testCase + "-";
        String suffix = extension(testCase);
        if (!name.startsWith(prefix) || !name.endsWith(suffix)
                || name.length() != prefix.length() + 36 + suffix.length()) {
            throw new IOException("invalid source name");
        }
        String nonce = name.substring(prefix.length(), prefix.length() + 36);
        if (!UUID.fromString(nonce).toString().equals(nonce)) throw new IOException("invalid nonce");
        File source = new File(directory, name);
        if (!source.equals(source.getCanonicalFile())) throw new IOException("invalid source path");
        return source;
    }

    private static String extension(String testCase) {
        if ("text-a".equals(testCase)) return ".txt";
        if ("zip-b".equals(testCase)) return ".zip";
        if ("binary-c".equals(testCase)) return ".bin";
        throw new IllegalArgumentException("unsupported case");
    }

    private static byte[] testBytes(String testCase) throws IOException {
        extension(testCase);
        if ("text-a".equals(testCase)) {
            byte[] bytes = new byte[200];
            byte[] pattern = "Observatory file laboratory test.\n".getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < bytes.length; i++) bytes[i] = pattern[i % pattern.length];
            return bytes;
        }
        byte[] bytes = new byte["binary-c".equals(testCase) ? 1024 * 1024 : 256 * 1024 - 120];
        new Random(80793200L).nextBytes(bytes);
        if ("binary-c".equals(testCase)) return bytes;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry = new ZipEntry("payload.bin");
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setTime(946684800000L);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(bytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static void writeSync(File target, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void syncDirectory(File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("not a journal directory");
        try {
            java.io.FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(fd); } finally { Os.close(fd); }
        } catch (android.system.ErrnoException failure) {
            throw new IOException("journal directory sync failed", failure);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(Character.forDigit((value >> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static String status(JSONObject result) {
        String status = result.optString("status", "unknown");
        return "sent".equals(status) || "failed".equals(status) || "pending".equals(status)
                ? status : "unknown";
    }

    private static String evidence(JSONObject result) {
        long id = result.optLong("message_id", 0);
        StringBuilder text = new StringBuilder("message_id=" + Math.max(id, 0)
                + " status=" + status(result) + " source_released="
                + Boolean.TRUE.equals(result.opt("source_released")));
        for (String key : new String[]{"message_type", "row_count", "prepare", "upload",
                "send", "update", "first_cgi", "attachment_status", "attachment_total", "attachment_offset", "copy_bytes"}) {
            Object value = result.opt(key);
            if (value instanceof Number) text.append(' ').append(key).append('=').append(((Number) value).longValue());
        }
        for (String key : new String[]{"native_finished", "copy_verified", "server_id_present", "recovered_completed_attachment"}) {
            Object value = result.opt(key);
            if (value instanceof Boolean) text.append(' ').append(key).append('=').append(value);
        }
        return text.toString();
    }

    private static void event(String testCase, String stage, String details) {
        try { Log.i(TAG, "case=" + testCase + " stage=" + stage + " " + details); }
        catch (Throwable ignored) { /* Logging must never affect submission or its journal. */ }
    }
}
