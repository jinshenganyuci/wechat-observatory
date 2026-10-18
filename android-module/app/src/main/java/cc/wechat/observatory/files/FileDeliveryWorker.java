package cc.wechat.observatory.files;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import cc.wechat.observatory.util.BridgeLogger;

/** One bounded process-wide file queue; network reader and text handling never wait for it. */
public final class FileDeliveryWorker {
    public interface Download extends AutoCloseable {
        InputStream input();
        @Override void close() throws Exception;
    }
    public interface Host {
        String scope();
        String installation();
        void validate() throws Exception;
        void validateCached() throws Exception;
        JSONObject request(String action, FileJob job) throws Exception;
        Download download(FileJob job) throws Exception;
        void submit(FileJob job, File source, WeChat8079FileAdapter.Observer observer) throws Exception;
        WeChat8079FileAdapter.Proof inspect(FileJob job, long messageId) throws Exception;
        boolean acknowledge(FileJob job, String status, long messageId, String error) throws Exception;
    }
    private static FileDeliveryWorker shared;
    private final OutgoingFileJournal journal;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "observatory-file-delivery"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger deliverySlots = new AtomicInteger();
    private long nextRecovery;
    private int recoveryCursor;
    private final long observeMillis;

    public static synchronized FileDeliveryWorker get(Context context) throws IOException {
        if (shared == null) shared = new FileDeliveryWorker(new OutgoingFileJournal(context), 120000);
        return shared;
    }
    FileDeliveryWorker(OutgoingFileJournal journal, long observeMillis) { this.journal = journal; this.observeMillis = observeMillis; }

    public boolean enqueue(JSONObject item, Host host) throws Exception {
        FileJob job = new FileJob(item);
        String key = OutgoingFileJournal.key(host.scope(), job.id);
        JSONObject copy = new JSONObject(item.toString());
        if (pending.contains(key)) return true;
        // One successor can arrive immediately after HTTP ACK, before this worker unwinds.
        // Do not build a queue of leases that can expire while a native upload is running.
        int slots;
        do { slots = deliverySlots.get(); if (slots >= 2) return false; }
        while (!deliverySlots.compareAndSet(slots, slots + 1));
        if (!pending.add(key)) { deliverySlots.decrementAndGet(); return true; }
        try {
            worker.execute(() -> {
                try { deliver(copy, host); }
                catch (Throwable failure) { log(job.id, "journal_unavailable", 0); }
                finally { pending.remove(key); deliverySlots.decrementAndGet(); }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException busy) { pending.remove(key); deliverySlots.decrementAndGet(); return false; }
    }
    public boolean isBusy() { return deliverySlots.get() > 0 || pending.contains("recovery"); }

    /** Called by the existing registered worker; no new idle timer or wake source. */
    public synchronized void recover(Host host) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextRecovery || deliverySlots.get() > 0 || !pending.add("recovery")) return;
        nextRecovery = now + 60000;
        try {
            worker.execute(() -> {
                try {
                    journal.cleanupParts();
                    java.util.List<OutgoingFileJournal.Record> records = journal.records();
                    records.sort((a, b) -> a.key.compareTo(b.key));
                    int inspected = 0;
                    for (int visited = 0; visited < records.size() && inspected < 2; visited++) {
                        OutgoingFileJournal.Record record = records.get(Math.floorMod(recoveryCursor++, records.size()));
                        JSONObject data = journal.snapshot(record);
                        if (!host.scope().equals(data.getString("scope"))
                                || !host.installation().equals(data.getString("installation"))) continue;
                        if (data.optBoolean("acknowledged") && data.optBoolean("source_released")
                                && !"unknown".equals(data.getString("state"))) continue;
                        inspected++;
                        try { recoverOne(record, host); }
                        catch (Throwable ignored) { log(new FileJob(data.getJSONObject("item")).id, "recovery_pending", data.optLong("message_id")); }
                    }
                } catch (Throwable failure) { log(0, "recovery_unavailable", 0); }
                finally { pending.remove("recovery"); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { pending.remove("recovery"); }
    }

    public void clearSafeSources() {
        try {
            worker.execute(() -> {
                try { log(0, "configuration_cleared_retained_sources=" + journal.clearSafeSources(), 0); }
                catch (Exception failure) { log(0, "cleanup_pending", 0); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { log(0, "cleanup_pending", 0); }
    }

    void deliver(JSONObject item, Host host) throws Exception {
        FileJob job = new FileJob(item);
        host.validate();
        OutgoingFileJournal.Record record = journal.find(host.scope(), job.id);
        if (record != null) {
            if (journal.snapshot(record).optBoolean("intent_recorded")) { recoverOne(record, host); return; }
            JSONObject remote = host.request("status", job).getJSONObject("item");
            if (!remote.optString("native_started_at").isEmpty() || !"leased".equals(remote.optString("status"))
                    || !job.token.equals(remote.optString("lease_token"))) { recoverOne(record, host); return; }
            journal.prepareLease(record, item);
        } else record = journal.create(host.scope(), host.installation(), item);
        boolean intent = false;
        try {
            journal.reserveDownload(record);
            download(job, record, host);
            host.validate();
            // Persist before asking for the one-time server grant. A lost response is never retried.
            journal.state(record, "intent", ""); intent = true;
            JSONObject response = host.request("begin", job);
            if (!response.optBoolean("ok") || !response.optBoolean("granted"))
                throw new IOException("One-time send grant not confirmed");
            host.validate();
            journal.state(record, "started", "");
            OutgoingFileJournal.Record observed = record;
            AtomicBoolean observationFailed = new AtomicBoolean();
            log(job.id, "native_handoff", 0);
            host.submit(job, journal.source(record), (id, finished, ok) -> {
                try { journal.observe(observed, id, finished, ok); }
                catch (Exception failure) { observationFailed.set(true); }
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + observeMillis;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                host.validateCached();
                JSONObject snapshot = journal.snapshot(record);
                long messageId = snapshot.optLong("message_id");
                if (!observationFailed.get() && snapshot.optBoolean("native_finished") && messageId > 0) {
                    WeChat8079FileAdapter.Proof proof = host.inspect(job, messageId);
                    if (proof.sent && snapshot.optBoolean("phases_ok")) {
                        finish(record, job, host, "sent", ""); return;
                    }
                    if (proof.failed && !snapshot.optBoolean("phases_ok")) {
                        finish(record, job, host, "failed", "WeChat reported a terminal file failure"); return;
                    }
                }
                Thread.sleep(1000);
            }
            journal.state(record, "unknown", "Native file result not confirmed; automatic resend disabled");
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            journal.state(record, intent ? "unknown" : "failed", intent
                    ? "File send result unknown; automatic resend disabled"
                    : "Attachment could not be downloaded or validated before send");
            if (!intent) journal.release(record);
        }
        acknowledge(record, job, host);
    }

    private void download(FileJob job, OutgoingFileJournal.Record record, Host host) throws Exception {
        long total = 0, nextRenew = android.os.SystemClock.elapsedRealtime() + 15000;
        long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Download download = host.download(job); FileOutputStream output = new FileOutputStream(journal.part(record))) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = download.input().read(buffer)) != -1) {
                host.validateCached();
                long now = android.os.SystemClock.elapsedRealtime();
                if (now > deadline) throw new IOException("Attachment download deadline exceeded");
                if (now >= nextRenew) {
                    if (!host.request("renew", job).optBoolean("ok")) throw new IOException("File lease expired");
                    nextRenew = now + 15000;
                }
                total += count;
                if (total > job.size || total > FileJob.MAX_BYTES) throw new IOException("Attachment exceeds expected size");
                output.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
            if (total != job.size || !job.sha256.equals(FileJob.hex(digest.digest())))
                throw new IOException("Attachment integrity mismatch");
            output.getFD().sync();
        }
        journal.commitDownload(record);
        log(job.id, "download_verified", 0);
    }

    private void recoverOne(OutgoingFileJournal.Record record, Host host) throws Exception {
        host.validate();
        JSONObject data = journal.snapshot(record);
        FileJob job = new FileJob(data.getJSONObject("item"));
        JSONObject response = host.request("status", job);
        if (!response.optBoolean("ok")) throw new IOException("File status unavailable");
        JSONObject remote = response.getJSONObject("item");
        FileJob current = new FileJob(remote);
        if (current.id != job.id || !current.owner.equals(job.owner) || !current.recipient.equals(job.recipient)
                || !current.sha256.equals(job.sha256)
                || current.size != job.size || !current.attachmentId.equals(job.attachmentId))
            throw new IOException("File recovery scope changed");
        if (!current.token.equals(job.token)) {
            if (!data.optBoolean("intent_recorded")) return; // Fresh lease may retry only the pre-begin download.
            throw new IOException("Native file grant changed");
        }
        String state = data.getString("state");
        if ("prepared".equals(state)) {
            journal.state(record, "failed", "Interrupted before native send; automatic resend disabled");
            journal.release(record); acknowledge(record, job, host); return;
        }
        long id = data.optLong("message_id");
        if (id > 0 && !"failed".equals(state)) {
            WeChat8079FileAdapter.Proof proof = host.inspect(job, id);
            // Across restart there is no live task. Native attachment199/full offset is only
            // committed by this adapter's final CGI success path, unlike message status2.
            if (proof.sent) { finish(record, job, host, "sent", ""); return; }
            if (proof.failed && data.optBoolean("native_finished")) {
                finish(record, job, host, "failed", "WeChat reported a terminal file failure"); return;
            }
        }
        if (!"sent".equals(state) && !"failed".equals(state))
            journal.state(record, "unknown", "Native file result not confirmed; automatic resend disabled");
        acknowledge(record, job, host);
    }

    private void finish(OutgoingFileJournal.Record record, FileJob job, Host host, String state, String error) throws Exception {
        host.validate();
        journal.state(record, state, error);
        // Only ordinary files are accepted, so there is no parallel thumbnail source reader.
        journal.release(record);
        log(job.id, state, journal.snapshot(record).optLong("message_id"));
        acknowledge(record, job, host);
    }
    private void acknowledge(OutgoingFileJournal.Record record, FileJob job, Host host) throws Exception {
        host.validate();
        JSONObject data = journal.snapshot(record);
        if (host.acknowledge(job, data.getString("state"), data.optLong("message_id"), data.optString("error")))
            journal.acknowledged(record);
    }
    private static void log(long id, String stage, long messageId) {
        try { BridgeLogger.log("file outbox id=" + id + " stage=" + stage + " msgid=" + messageId); }
        catch (Throwable ignored) { }
    }
}
