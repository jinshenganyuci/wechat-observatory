package cc.wechat.observatory;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

public class SharedConfigStoreTest {
    private static final long DEADLINE = Long.MAX_VALUE;
    private static final String ID = "12345678-1234-4234-8234-123456789abc";

    @Test public void missingStoreCreatesOneStableEmptyEpochWithoutReserving() throws Exception {
        Backend disk = new Backend();
        Properties first = SharedConfigStore.read(new Files(disk), DEADLINE);
        Properties second = SharedConfigStore.read(new Files(disk), DEADLINE);
        assertEquals(first, second);
        assertEquals(2, first.size());
        assertTrue(ConfigWriteContract.validNonce(first.getProperty(SharedConfigStore.INSTALLATION)));
        assertFalse(first.containsKey("__generation_start"));
        assertEquals("0", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
    }

    @Test public void runtimeReservationsPersistBeforeReturnAndNeverOverlap() throws Exception {
        Backend disk = new Backend();
        Properties first = SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        assertEquals("1", first.getProperty("__generation_start"));
        assertEquals("1000000", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
        Properties second = SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        assertEquals("1000001", second.getProperty("__generation_start"));
        assertEquals("2000000", second.getProperty("__generation_limit"));
        assertEquals(first.getProperty(SharedConfigStore.INSTALLATION), second.getProperty(SharedConfigStore.INSTALLATION));
    }

    @Test public void concurrentHandlesUseLockForWholeReservation() throws Exception {
        Backend disk = new Backend();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Properties>> operations = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                operations.add(workers.submit(() -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE)));
            }
            Set<String> starts = new HashSet<>();
            for (Future<Properties> operation : operations) {
                assertTrue(starts.add(operation.get(5, TimeUnit.SECONDS).getProperty("__generation_start")));
            }
            assertEquals(12, starts.size());
            assertEquals("12000000", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
            assertEquals(12, disk.lockCalls);
        } finally { workers.shutdownNow(); }
    }

    @Test public void failedLockDoesNotReadOrWriteAnyConfiguration() {
        Backend disk = new Backend();
        disk.failLock = true;
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
        assertTrue(disk.files.isEmpty());
        assertEquals(0, disk.fileOperations);
    }

    @Test public void clearAndOrdinarySaveKeepEpochAndHighWatermark() throws Exception {
        Backend disk = new Backend();
        Properties saved = SharedConfigStore.saveAndLoad(new Files(disk), config(), true, DEADLINE);
        Properties cleared = SharedConfigStore.saveAndLoad(new Files(disk), new Properties(), false, DEADLINE);
        assertEquals(saved.getProperty(SharedConfigStore.INSTALLATION), cleared.getProperty(SharedConfigStore.INSTALLATION));
        assertFalse(cleared.containsKey("api_key"));
        assertEquals(2, cleared.size());
        assertEquals("1000000", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
        assertNotEquals(saved.getProperty(SharedConfigStore.REVISION), cleared.getProperty(SharedConfigStore.REVISION));
        Properties reSaved = SharedConfigStore.saveAndLoad(new Files(disk), config(), false, DEADLINE);
        assertFalse(reSaved.containsKey("__generation_start"));
        assertEquals("1000000", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
    }

    @Test public void saveAndReserveAreOneVerifiedTransaction() throws Exception {
        Backend disk = new Backend();
        SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        Properties result = SharedConfigStore.saveAndLoad(new Files(disk), config(), true, DEADLINE);
        Properties durable = disk.persisted();
        assertEquals("1000001", result.getProperty("__generation_start"));
        assertEquals(result.getProperty("__generation_limit"), durable.getProperty(SharedConfigStore.HIGH_WATERMARK));
        assertEquals(result.getProperty(SharedConfigStore.REVISION), durable.getProperty(SharedConfigStore.REVISION));
        assertEquals(result.getProperty("api_key"), durable.getProperty("api_key"));
    }

    @Test public void crashBeforeCommitRestoresOldBackupAndDoesNotPublishUncommittedRange() throws Exception {
        Backend disk = new Backend();
        SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        disk.failNextCommitRename = true;
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
        assertFalse(disk.files.containsKey(SharedConfigStore.MAIN));
        assertTrue(disk.files.containsKey(SharedConfigStore.BACKUP));
        Properties recovered = SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        assertEquals("1000001", recovered.getProperty("__generation_start"));
        assertFalse(disk.files.containsKey(SharedConfigStore.BACKUP));
        assertFalse(disk.files.containsKey(SharedConfigStore.NEW));
    }

    @Test public void crashAfterCommitNeverRewindsToBackupGeneration() throws Exception {
        Backend disk = new Backend();
        SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        disk.failReadAfterNextCommit = true;
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
        assertTrue(disk.files.containsKey(SharedConfigStore.MAIN));
        assertTrue(disk.files.containsKey(SharedConfigStore.BACKUP));
        Properties recovered = SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        assertEquals("2000001", recovered.getProperty("__generation_start"));
        assertEquals("3000000", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
    }

    @Test public void corruptMainDoesNotFallBackToOlderValidBackup() throws Exception {
        Backend disk = new Backend();
        SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        disk.files.put(SharedConfigStore.BACKUP, disk.files.get(SharedConfigStore.MAIN).clone());
        disk.files.put(SharedConfigStore.MAIN, "unknown=invalid\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
        assertTrue(disk.files.containsKey(SharedConfigStore.BACKUP));
    }

    @Test public void interruptedInitialWriteCannotBecomeAnAcceptedConfiguration() throws Exception {
        Backend disk = new Backend();
        disk.files.put(SharedConfigStore.NEW, encoded(config()));
        Properties loaded = SharedConfigStore.read(new Files(disk), DEADLINE);
        assertFalse(loaded.containsKey("api_key"));
        assertFalse(disk.files.containsKey(SharedConfigStore.NEW));
    }

    @Test public void manualBusinessOnlyFileGetsDurableMetadata() throws Exception {
        Backend disk = new Backend();
        disk.files.put(SharedConfigStore.MAIN, encoded(config()));
        Properties loaded = SharedConfigStore.read(new Files(disk), DEADLINE);
        assertTrue(ConfigWriteContract.validNonce(loaded.getProperty(SharedConfigStore.REVISION)));
        assertEquals(loaded.getProperty(SharedConfigStore.INSTALLATION), disk.persisted().getProperty(SharedConfigStore.INSTALLATION));
        assertEquals("0", disk.persisted().getProperty(SharedConfigStore.HIGH_WATERMARK));
    }

    @Test public void existingEpochWithoutCeilingCannotResetItsGeneration() throws Exception {
        Backend disk = new Backend();
        Properties malformed = config();
        malformed.setProperty(SharedConfigStore.INSTALLATION, ID);
        disk.files.put(SharedConfigStore.MAIN, encoded(malformed));
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
        malformed.setProperty(SharedConfigStore.HIGH_WATERMARK, "-1");
        disk.files.put(SharedConfigStore.MAIN, encoded(malformed));
        assertThrows(IOException.class, () -> SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE));
    }

    @Test public void invalidUuidDuplicateKeyAndOversizedFileAreRejected() throws Exception {
        Backend disk = new Backend();
        Properties malformed = config();
        malformed.setProperty(SharedConfigStore.REVISION, "invalid-uuid");
        disk.files.put(SharedConfigStore.MAIN, encoded(malformed));
        assertThrows(IOException.class, () -> SharedConfigStore.read(new Files(disk), DEADLINE));
        disk.files.put(SharedConfigStore.MAIN, "api_key=first\napi_key=second\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> SharedConfigStore.read(new Files(disk), DEADLINE));
        disk.files.put(SharedConfigStore.MAIN, new byte[SharedConfigStore.MAX_BYTES + 1]);
        assertThrows(IOException.class, () -> SharedConfigStore.read(new Files(disk), DEADLINE));
    }

    @Test public void callerCannotOverwriteInternalMetadataThroughSave() {
        Backend disk = new Backend();
        Properties input = config();
        input.setProperty(SharedConfigStore.HIGH_WATERMARK, "0");
        assertThrows(IOException.class, () -> SharedConfigStore.saveAndLoad(new Files(disk), input, true, DEADLINE));
        assertTrue(disk.files.isEmpty());
    }

    @Test public void migrationPreservesEpochAndReservedCeiling() throws Exception {
        Backend disk = new Backend();
        assertTrue(SharedConfigStore.migratePrivate(new Files(disk), () -> {
            Properties old = config();
            old.setProperty(SharedConfigStore.INSTALLATION, ID);
            old.setProperty(SharedConfigStore.HIGH_WATERMARK, "2000000");
            return old;
        }, new Marker(), DEADLINE));
        Properties runtime = SharedConfigStore.loadForRuntime(new Files(disk), DEADLINE);
        assertEquals(ID, runtime.getProperty(SharedConfigStore.INSTALLATION));
        assertEquals("2000001", runtime.getProperty("__generation_start"));
        assertEquals("test-key", runtime.getProperty("api_key"));
    }

    @Test public void migrationNeverReadsOldSourcesOverAnExistingClear() throws Exception {
        Backend disk = new Backend();
        Properties cleared = SharedConfigStore.saveAndLoad(new Files(disk), new Properties(), false, DEADLINE);
        AtomicBoolean oldRead = new AtomicBoolean();
        assertFalse(SharedConfigStore.migratePrivate(new Files(disk), () -> {
            oldRead.set(true);
            return config();
        }, new Marker(), DEADLINE));
        assertFalse(oldRead.get());
        assertEquals(cleared, SharedConfigStore.read(new Files(disk), DEADLINE));
    }

    @Test public void deletingPublicConfigurationAfterMigrationDoesNotReviveOldPrivateKey() throws Exception {
        Backend disk = new Backend();
        Marker marker = new Marker();
        assertTrue(SharedConfigStore.migratePrivate(new Files(disk), SharedConfigStoreTest::config, marker, DEADLINE));
        assertTrue(marker.complete);
        disk.files.clear(); // User explicitly removes the public directory.
        AtomicBoolean oldRead = new AtomicBoolean();
        assertFalse(SharedConfigStore.migratePrivate(new Files(disk), () -> {
            oldRead.set(true);
            return config();
        }, marker, DEADLINE));
        assertFalse(oldRead.get());
        Properties empty = SharedConfigStore.read(new Files(disk), DEADLINE);
        assertFalse(empty.containsKey("api_key"));
    }

    @Test public void existingPublicClearAlsoPermanentlyDisablesPrivateMigration() throws Exception {
        Backend disk = new Backend();
        Marker marker = new Marker();
        SharedConfigStore.saveAndLoad(new Files(disk), new Properties(), false, DEADLINE);
        assertFalse(SharedConfigStore.migratePrivate(new Files(disk), SharedConfigStoreTest::config, marker, DEADLINE));
        assertTrue(marker.complete);
        disk.files.clear();
        assertFalse(SharedConfigStore.migratePrivate(new Files(disk), () -> {
            fail("An existing public clear must remain authoritative after deletion");
            return config();
        }, marker, DEADLINE));
    }

    @Test public void failedMarkerWriteCanRetryWithoutOverwritingPublicConfiguration() throws Exception {
        Backend disk = new Backend();
        Marker marker = new Marker();
        marker.failWrite = true;
        assertThrows(IOException.class, () -> SharedConfigStore.migratePrivate(new Files(disk),
                SharedConfigStoreTest::config, marker, DEADLINE));
        assertFalse(marker.complete);
        Properties committed = disk.persisted();
        marker.failWrite = false;
        assertFalse(SharedConfigStore.migratePrivate(new Files(disk), () -> {
            fail("A committed public file must not be imported over again");
            return config();
        }, marker, DEADLINE));
        assertTrue(marker.complete);
        assertEquals(committed, disk.persisted());
    }

    @Test public void failedPublicWriteDoesNotMarkMigrationComplete() {
        Backend disk = new Backend();
        Marker marker = new Marker();
        disk.failNextCommitRename = true;
        assertThrows(IOException.class, () -> SharedConfigStore.migratePrivate(new Files(disk),
                SharedConfigStoreTest::config, marker, DEADLINE));
        assertFalse(marker.complete);
    }

    private static final class Marker implements SharedConfigStore.MigrationMarker {
        boolean complete;
        boolean failWrite;
        @Override public boolean isComplete() { return complete; }
        @Override public void complete() throws IOException {
            if (failWrite) throw new IOException("test marker persistence failure");
            complete = true;
        }
    }

    private static Properties config() {
        Properties values = new Properties();
        values.setProperty("bridge_url", "https://example.test/gateway");
        values.setProperty("api_key", "test-key");
        return values;
    }

    private static byte[] encoded(Properties state) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        state.store(output, "test");
        return output.toByteArray();
    }

    private static final class Backend {
        final Map<String, byte[]> files = new HashMap<>();
        final ReentrantLock mutex = new ReentrantLock();
        int lockCalls;
        int fileOperations;
        boolean failLock;
        boolean failNextCommitRename;
        boolean failReadAfterNextCommit;
        boolean failNextMainRead;

        Properties persisted() throws IOException {
            Properties state = new Properties();
            state.load(new java.io.ByteArrayInputStream(files.get(SharedConfigStore.MAIN)));
            return state;
        }
    }

    private static final class Files extends SharedConfigFiles {
        private final Backend disk;
        Files(Backend disk) { this.disk = disk; }

        @Override Closeable lock(long deadlineElapsedMs) throws IOException {
            disk.lockCalls++;
            if (disk.failLock) throw new IOException("test lock unavailable");
            disk.mutex.lock();
            return disk.mutex::unlock;
        }
        private void requireLock() {
            assertTrue("Every file operation must be protected by the cross-process lock", disk.mutex.isHeldByCurrentThread());
            disk.fileOperations++;
        }
        @Override boolean exists(String name) { requireLock(); return disk.files.containsKey(name); }
        @Override byte[] read(String name, int maxBytes) throws IOException {
            requireLock();
            if (SharedConfigStore.MAIN.equals(name) && disk.failNextMainRead) {
                disk.failNextMainRead = false;
                throw new IOException("test crash after commit");
            }
            byte[] bytes = disk.files.get(name);
            if (bytes == null || bytes.length > maxBytes) throw new IOException("test missing or large file");
            return bytes.clone();
        }
        @Override void writeSync(String name, byte[] bytes) {
            requireLock();
            disk.files.put(name, bytes.clone());
        }
        @Override void rename(String from, String to) throws IOException {
            requireLock();
            boolean commit = SharedConfigStore.NEW.equals(from) && SharedConfigStore.MAIN.equals(to);
            if (commit && disk.failNextCommitRename) {
                disk.failNextCommitRename = false;
                throw new IOException("test crash before commit");
            }
            if (!disk.files.containsKey(from) || disk.files.containsKey(to)) throw new IOException("test rename failure");
            disk.files.put(to, disk.files.remove(from));
            if (commit && disk.failReadAfterNextCommit) {
                disk.failReadAfterNextCommit = false;
                disk.failNextMainRead = true;
            }
        }
        @Override void delete(String name) { requireLock(); disk.files.remove(name); }
        @Override public void close() { }
    }
}
