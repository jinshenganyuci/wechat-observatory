package cc.wechat.observatory.files;

import android.util.AtomicFile;
import cc.wechat.observatory.WeChatConfigStoreTest;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real staging files/AtomicFile transactions plus a fake native/server boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 35}, shadows = WeChatConfigStoreTest.RealFileOs.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FileDeliveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private OutgoingFileJournal journal;
    private final List<FileDeliveryWorker> workers = new ArrayList<>();
    private static final byte[] CONTENT = "verified ordinary file\n".getBytes(StandardCharsets.UTF_8);
    private static final String SCOPE = "server-installation-owner";

    @Before public void setUp() throws Exception {
        root = temporary.newFolder("no_backup").getCanonicalFile();
        journal = openJournal();
    }
    private OutgoingFileJournal openJournal() throws Exception {
        // Robolectric's Os.open does not implement directory descriptors. Force the actual
        // host directory through NIO; all production serialization/AtomicFile code is shared.
        return new OutgoingFileJournal(root, dir -> {
            try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) { channel.force(true); }
        });
    }
    @After public void stopWorkers() throws Exception {
        java.lang.reflect.Field field = FileDeliveryWorker.class.getDeclaredField("worker"); field.setAccessible(true);
        for (FileDeliveryWorker worker : workers) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(worker);
            executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
    private FileDeliveryWorker worker(long observeMillis) {
        FileDeliveryWorker worker = new FileDeliveryWorker(journal, observeMillis); workers.add(worker); return worker;
    }
    private static JSONObject item(long id, String token) throws Exception {
        return new JSONObject().put("id", id).put("kind", "file").put("owner_wxid", "owner")
                .put("device", "phone").put("wxid", "filehelper").put("lease_token", token).put("status", "leased")
                .put("attachment", new JSONObject().put("id", "attachment-" + id).put("name", "report.PDF")
                        .put("size", CONTENT.length).put("sha256", FileJob.digest(CONTENT)));
    }
    private OutgoingFileJournal.Record record(long id) throws Exception { return journal.find(SCOPE, id); }
    private void stage(OutgoingFileJournal.Record record) throws Exception {
        journal.reserveDownload(record); Files.write(journal.part(record).toPath(), CONTENT); journal.commitDownload(record);
    }

    @Test public void filenameContractMatchesServerAndKeepsSafeExtension() throws Exception {
        assertEquals("😀:report.txt", FileJob.safeName("😀:report.txt"));
        assertEquals("jpg", FileJob.safeName("jpg"));
        assertEquals("mp4", FileJob.safeName("mp4"));
        assertEquals(240, FileJob.safeName(String.join("", Collections.nCopies(236, "a")) + ".txt").length());
        for (String bad : Arrays.asList("../report.txt", "a\\b.txt", "a\u0001.txt", "a\u0085.txt", "\u00a0a.txt", "a.txt ", ".", "..", "x.JPG", "x.mp4", "\ud800.txt"))
            assertThrows(bad, IllegalArgumentException.class, () -> FileJob.safeName(bad));
        assertThrows(IllegalArgumentException.class, () -> FileJob.safeName(String.join("", Collections.nCopies(237, "a")) + ".txt"));
        OutgoingFileJournal.Record record = journal.create(SCOPE, "installation", item(1, "lease"));
        assertTrue(journal.source(record).getName().endsWith(".source.PDF"));
    }
    @Test public void emptyReadNeverCreatesDirectory() throws Exception {
        assertNull(record(1)); assertTrue(journal.records().isEmpty()); journal.cleanupParts();
        assertFalse(new File(root, OutgoingFileJournal.DIRECTORY).exists());
    }
    @Test public void intentAndNativeMessageSurviveNewJournalInstance() throws Exception {
        OutgoingFileJournal.Record record = journal.create(SCOPE, "installation", item(1, "lease"));
        stage(record); journal.state(record, "intent", ""); journal.state(record, "started", "");
        journal.observe(record, 77, false, false);
        journal = openJournal(); JSONObject recovered = journal.snapshot(record(1));
        assertTrue(recovered.getBoolean("intent_recorded")); assertEquals("started", recovered.getString("state"));
        assertEquals(77, recovered.getLong("message_id")); assertArrayEquals(CONTENT, Files.readAllBytes(journal.source(record(1)).toPath()));
    }
    @Test public void interruptedAtomicWriteRestoresPriorIntent() throws Exception {
        OutgoingFileJournal.Record record = journal.create(SCOPE, "installation", item(1, "lease"));
        journal.state(record, "intent", "");
        File main = new File(new File(root, OutgoingFileJournal.DIRECTORY), record.key + ".json");
        AtomicFile atomic = new AtomicFile(main); FileOutputStream unfinished = atomic.startWrite();
        unfinished.write("truncated".getBytes(StandardCharsets.UTF_8)); unfinished.getFD().sync(); unfinished.close();
        assertEquals("intent", openJournal().snapshot(openJournal().find(SCOPE, 1)).getString("state"));
    }
    @Test public void corruptJournalAndSymlinkAreNotReplaced() throws Exception {
        OutgoingFileJournal.Record record = journal.create(SCOPE, "installation", item(1, "lease"));
        File main = new File(new File(root, OutgoingFileJournal.DIRECTORY), record.key + ".json");
        Files.write(main.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> journal.find(SCOPE, 1));
        assertEquals("broken", new String(Files.readAllBytes(main.toPath()), StandardCharsets.UTF_8));
        Files.delete(main.toPath()); Files.createSymbolicLink(main.toPath(), new File(root, "missing-target").toPath());
        assertThrows(IOException.class, () -> journal.find(SCOPE, 1));
    }
    @Test public void clearRetainsUnknownButPrunesAcknowledgedReleasedTerminal() throws Exception {
        OutgoingFileJournal.Record pending = journal.create(SCOPE, "installation", item(1, "lease"));
        stage(pending); journal.state(pending, "intent", ""); journal.state(pending, "unknown", "uncertain");
        OutgoingFileJournal.Record done = journal.create(SCOPE, "installation", item(2, "lease2"));
        stage(done); journal.state(done, "sent", ""); journal.release(done); journal.acknowledged(done);
        OutgoingFileJournal.Record prepared = journal.create(SCOPE, "installation", item(3, "lease3")); stage(prepared);
        assertEquals(1, journal.clearSafeSources());
        assertTrue(journal.source(pending).exists()); assertNull(record(2)); assertFalse(journal.source(prepared).exists());
        assertNotNull(record(1)); assertNotNull(record(3));
    }
    @Test public void verifiedCompletionCallsNativeOnceAndCleansOnlyOwnSource() throws Exception {
        Host host = new Host(item(1, "lease")); FileDeliveryWorker worker = worker(1000);
        File unrelated = new File(root, "unrelated"); Files.write(unrelated.toPath(), CONTENT);
        worker.deliver(host.remote, host); worker.deliver(host.remote, host);
        assertEquals(1, host.submits.get()); assertEquals(1, host.begins.get());
        assertEquals("sent", journal.snapshot(record(1)).getString("state"));
        assertTrue(journal.snapshot(record(1)).getBoolean("acknowledged")); assertFalse(journal.source(record(1)).exists());
        assertArrayEquals(CONTENT, Files.readAllBytes(unrelated.toPath()));
    }
    @Test public void lostBeginResponseNeverCallsNativeOrRequestsAnotherGrant() throws Exception {
        Host host = new Host(item(1, "lease")); host.loseGrantResponse = true;
        FileDeliveryWorker worker = worker(1000); worker.deliver(host.remote, host);
        journal = openJournal(); worker = worker(1000); worker.deliver(host.remote, host);
        assertEquals(1, host.begins.get()); assertEquals(0, host.submits.get());
        assertEquals("unknown", journal.snapshot(record(1)).getString("state")); assertTrue(journal.source(record(1)).exists());
    }
    @Test public void checksumFailureCannotBeginAndRemovesPartialFile() throws Exception {
        Host host = new Host(item(1, "lease")); host.bytes = "bad".getBytes(StandardCharsets.UTF_8);
        worker(1000).deliver(host.remote, host);
        assertEquals(0, host.begins.get()); assertEquals(0, host.submits.get());
        assertEquals("failed", journal.snapshot(record(1)).getString("state"));
        assertFalse(journal.source(record(1)).exists()); assertFalse(journal.part(record(1)).exists());
    }
    @Test public void newPreBeginLeaseCanRetryDownloadWithoutOldTokenDeadlock() throws Exception {
        OutgoingFileJournal.Record old = journal.create(SCOPE, "installation", item(1, "old")); stage(old);
        Host host = new Host(item(1, "new")); worker(1000).deliver(host.remote, host);
        assertEquals(1, host.submits.get()); assertEquals("new", journal.snapshot(record(1)).getJSONObject("item").getString("lease_token"));
        assertEquals("sent", journal.snapshot(record(1)).getString("state"));
    }
    @Test public void changedLeaseCannotRetryAnyPersistedBeginIntent() throws Exception {
        OutgoingFileJournal.Record old = journal.create(SCOPE, "installation", item(1, "old")); stage(old); journal.state(old, "intent", "");
        Host host = new Host(item(1, "new"));
        assertThrows(IOException.class, () -> worker(1000).deliver(host.remote, host));
        assertEquals(0, host.begins.get()); assertEquals(0, host.submits.get()); assertTrue(journal.source(old).exists());
    }
    @Test public void configurationChangeAfterHandoffRetainsUnknownSource() throws Exception {
        Host host = new Host(item(1, "lease")); host.invalidateAfterSubmit = true;
        assertThrows(IOException.class, () -> worker(1000).deliver(host.remote, host));
        assertEquals(1, host.submits.get()); assertEquals("unknown", journal.snapshot(record(1)).getString("state"));
        assertTrue(journal.source(record(1)).exists()); assertEquals(0, host.acks.get());
    }
    @Test public void nativeTerminalFailureWithFinishedObservationCleansSource() throws Exception {
        Host host = new Host(item(1, "lease")); host.nativeFailed = true;
        worker(1000).deliver(host.remote, host);
        assertEquals("failed", journal.snapshot(record(1)).getString("state")); assertFalse(journal.source(record(1)).exists());
    }
    @Test public void unknownRecoveryUsesSavedMessageIdWithoutAnotherNativeSend() throws Exception {
        Host host = new Host(item(1, "lease")); host.finishEvent = false;
        worker(0).deliver(host.remote, host);
        assertEquals("unknown", journal.snapshot(record(1)).getString("state")); assertTrue(journal.source(record(1)).exists());
        journal = openJournal(); worker(1000).deliver(host.remote, host);
        assertEquals(1, host.submits.get()); assertEquals("sent", journal.snapshot(record(1)).getString("state"));
        assertFalse(journal.source(record(1)).exists());
    }
    @Test public void recoveryDoesNotReplaceTerminalSentWithUnknownWhenNativeCopyUnavailable() throws Exception {
        Host host = new Host(item(1, "lease")); host.acceptAck = false; FileDeliveryWorker worker = worker(1000);
        worker.deliver(host.remote, host); host.proofPending = true; host.acceptAck = true;
        worker.deliver(host.remote, host);
        assertEquals("sent", journal.snapshot(record(1)).getString("state")); assertEquals(1, host.submits.get());
    }
    @Test public void duplicatePendingMergesAndAckSuccessorDoesNotWaitForLeaseExpiry() throws Exception {
        FileDeliveryWorker worker = worker(1000); Host next = new Host(item(2, "lease2"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), secondAck = new CountDownLatch(1);
        Host first = new Host(item(1, "lease")) {
            @Override public FileDeliveryWorker.Download download(FileJob job) throws Exception {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return super.download(job);
            }
            @Override public boolean acknowledge(FileJob job, String status, long id, String error) throws Exception {
                assertTrue(worker.enqueue(next.remote, next)); return super.acknowledge(job, status, id, error);
            }
        };
        next.onAck = secondAck::countDown;
        assertTrue(worker.enqueue(first.remote, first)); assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertTrue(worker.enqueue(first.remote, first)); release.countDown();
        assertTrue(secondAck.await(5, TimeUnit.SECONDS)); assertEquals(1, first.submits.get()); assertEquals(1, next.submits.get());
    }
    @Test public void boundedQueueRejectsThirdFileWithoutCreatingJournal() throws Exception {
        FileDeliveryWorker worker = worker(1000); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Host first = new Host(item(1, "lease")) {
            @Override public FileDeliveryWorker.Download download(FileJob job) throws Exception {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return super.download(job);
            }
        };
        try {
            assertTrue(worker.enqueue(first.remote, first)); assertTrue(entered.await(3, TimeUnit.SECONDS));
            Host second = new Host(item(2, "lease2")), third = new Host(item(3, "lease3"));
            assertTrue(worker.enqueue(second.remote, second)); assertFalse(worker.enqueue(third.remote, third)); assertNull(record(3));
        } finally { release.countDown(); }
    }

    private static class Host implements FileDeliveryWorker.Host {
        final JSONObject remote;
        final AtomicInteger submits = new AtomicInteger(), begins = new AtomicInteger(), acks = new AtomicInteger();
        byte[] bytes = CONTENT;
        boolean loseGrantResponse, nativeFailed, invalidateAfterSubmit, invalid, proofPending;
        boolean finishEvent = true, acceptAck = true;
        Runnable onAck = () -> {};
        Host(JSONObject remote) { this.remote = remote; }
        @Override public String scope() { return SCOPE; }
        @Override public String installation() { return "installation"; }
        @Override public void validate() throws IOException { if (invalid) throw new IOException("scope changed"); }
        @Override public void validateCached() throws IOException { validate(); }
        @Override public JSONObject request(String action, FileJob job) throws Exception {
            if ("begin".equals(action)) {
                begins.incrementAndGet(); remote.put("native_started_at", "2026-10-18T00:00:00Z");
                if (loseGrantResponse) throw new IOException("grant response lost");
                return new JSONObject().put("ok", true).put("granted", true).put("item", remote);
            }
            return new JSONObject().put("ok", true).put("item", remote);
        }
        @Override public FileDeliveryWorker.Download download(FileJob job) throws Exception {
            return new FileDeliveryWorker.Download() {
                private final InputStream input = new ByteArrayInputStream(bytes);
                @Override public InputStream input() { return input; }
                @Override public void close() throws IOException { input.close(); }
            };
        }
        @Override public void submit(FileJob job, File source, WeChat8079FileAdapter.Observer observer) throws Exception {
            submits.incrementAndGet(); assertArrayEquals(CONTENT, Files.readAllBytes(source.toPath()));
            observer.observed(77, finishEvent, !nativeFailed); if (invalidateAfterSubmit) invalid = true;
        }
        @Override public WeChat8079FileAdapter.Proof inspect(FileJob job, long id) {
            assertEquals(77, id); return new WeChat8079FileAdapter.Proof(!nativeFailed && !proofPending, nativeFailed);
        }
        @Override public boolean acknowledge(FileJob job, String status, long id, String error) throws Exception {
            acks.incrementAndGet(); remote.put("status", status); onAck.run(); return acceptAck;
        }
    }
}
