package cc.wechat.observatory;

import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.util.AtomicFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import cc.wechat.observatory.config.BridgeConfig;

import static org.junit.Assert.*;

/** Exercise real files and Android's AtomicFile, including both backup implementations. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class WeChatConfigStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HostContext context;
    private File data;
    private File root;
    private File directory;
    private File main;

    @Before public void prepare() throws IOException {
        data = temporary.newFolder("wechat-data").getCanonicalFile();
        root = new File(data, "no_backup");
        directory = new File(root, WeChatConfigStore.DIRECTORY);
        main = new File(directory, WeChatConfigStore.MAIN);
        context = new HostContext(data);
        BridgeConfig.clearRuntimeSettings();
    }

    @After public void clearRuntime() { BridgeConfig.clearRuntimeSettings(); }

    @Test public void missingConfigurationNeverCreatesEvenTheFrameworkDirectory() throws Exception {
        assertTrue(WeChatConfigStore.read(context).isEmpty());
        assertTrue(WeChatConfigStore.loadForRuntime(context).isEmpty());
        WeChatConfigStore.delete(context);
        assertTrue(WeChatConfigStore.saveAndLoad(context, new Properties(), true).isEmpty());
        assertEquals(0, context.noBackupCalls);
        assertFalse(root.exists());
        assertArrayEquals(new String[0], data.list());
    }

    @Test public void existingFrameworkDirectoryDoesNotCauseConfigurationCreation() throws Exception {
        assertTrue(root.mkdir());
        assertTrue(WeChatConfigStore.read(context).isEmpty());
        assertTrue(WeChatConfigStore.loadForRuntime(context).isEmpty());
        assertFalse(directory.exists());
        assertArrayEquals(new String[0], root.list());
    }

    @Test public void persistenceUsesOnlyTheIndependentDirectoryAndReturnsCopies() throws Exception {
        File hostBusiness = new File(data, "host-business");
        Files.write(hostBusiness.toPath(), new byte[]{7, 8, 9});
        Properties saved = WeChatConfigStore.saveAndLoad(context, business(), false);
        assertEquals("dummy-key", saved.getProperty("api_key"));
        assertEquals("1", saved.getProperty("enabled"));
        assertEquals("1", saved.getProperty("outbox_websocket_enabled"));
        assertTrue(ConfigWriteContract.validNonce(saved.getProperty(WeChatConfigStore.INSTALLATION)));
        assertTrue(ConfigWriteContract.validNonce(saved.getProperty(WeChatConfigStore.REVISION)));
        assertFalse(saved.containsKey(WeChatConfigStore.HIGH_WATERMARK));
        assertFalse(saved.containsKey("__generation_start"));
        assertArrayEquals(new String[]{WeChatConfigStore.MAIN}, directory.list());
        saved.setProperty("api_key", "changed-in-memory");
        Properties reloaded = WeChatConfigStore.read(new HostContext(data));
        assertEquals("dummy-key", reloaded.getProperty("api_key"));
        assertEquals("0", diskState().getProperty(WeChatConfigStore.HIGH_WATERMARK));
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(hostBusiness.toPath()));
    }

    @Test public void restartReservesANewRangeAndSavingDoesNotRewindIt() throws Exception {
        Properties first = WeChatConfigStore.saveAndLoad(context, business(), true);
        assertRange(first, 1, 1_000_000);
        String installation = first.getProperty(WeChatConfigStore.INSTALLATION);
        Properties restarted = WeChatConfigStore.loadForRuntime(new HostContext(data));
        assertRange(restarted, 1_000_001, 2_000_000);
        assertEquals(installation, restarted.getProperty(WeChatConfigStore.INSTALLATION));

        Properties changed = business();
        changed.setProperty("api_key", "replacement-key");
        changed.setProperty("outbox_websocket_enabled", "0");
        Properties saved = WeChatConfigStore.saveAndLoad(context, changed, false);
        assertEquals(installation, saved.getProperty(WeChatConfigStore.INSTALLATION));
        assertNotEquals(first.getProperty(WeChatConfigStore.REVISION), saved.getProperty(WeChatConfigStore.REVISION));
        assertFalse(saved.containsKey("__generation_start"));
        assertEquals("2000000", diskState().getProperty(WeChatConfigStore.HIGH_WATERMARK));
        assertEquals("0", WeChatConfigStore.read(context).getProperty("outbox_websocket_enabled"));
        assertRange(WeChatConfigStore.loadForRuntime(new HostContext(data)), 2_000_001, 3_000_000);
    }

    @Test public void simultaneousLoadsPublishDistinctDurablyReservedRanges() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Properties>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                pending.add(workers.submit(() -> WeChatConfigStore.loadForRuntime(context)));
            }
            Set<String> starts = new HashSet<>();
            for (Future<Properties> reply : pending) starts.add(reply.get().getProperty("__generation_start"));
            assertEquals(new HashSet<>(Arrays.asList("1000001", "2000001", "3000001", "4000001")), starts);
            assertEquals("5000000", diskState().getProperty(WeChatConfigStore.HIGH_WATERMARK));
        } finally { workers.shutdownNow(); }
    }

    @Test public void emptySaveRemovesAllOwnedFilesAndDoesNotTouchNeighborFiles() throws Exception {
        Properties initial = WeChatConfigStore.saveAndLoad(context, business(), true);
        File neighbor = new File(root, "wechat-owned-file");
        Files.write(neighbor.toPath(), new byte[]{4, 5, 6});
        Files.write(new File(main + ".bak").toPath(), new byte[]{0});
        Files.write(new File(main + ".new").toPath(), new byte[]{0});
        assertTrue(WeChatConfigStore.saveAndLoad(context, new Properties(), true).isEmpty());
        assertFalse(directory.exists());
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(neighbor.toPath()));
        assertTrue(WeChatConfigStore.read(context).isEmpty());
        assertTrue(WeChatConfigStore.loadForRuntime(context).isEmpty());
        assertFalse(directory.exists());

        Properties recreated = WeChatConfigStore.saveAndLoad(context, business(), true);
        assertNotEquals(initial.getProperty(WeChatConfigStore.INSTALLATION),
                recreated.getProperty(WeChatConfigStore.INSTALLATION));
        assertRange(recreated, 1, 1_000_000);
    }

    @Test public void clientClearAndLaterBootstrapStayEmptyWithoutRecreation() throws Exception {
        BridgeConfig.acceptRuntimeSettings(WeChatConfigStore.saveAndLoad(context, business(), true));
        assertTrue(BridgeConfig.hasRuntimeSession());
        Properties cleared = ConfigFileClient.readOrWrite(context, false, false, Collections.emptyMap());
        assertTrue(cleared.isEmpty());
        assertFalse(directory.exists());
        BridgeConfig.acceptRuntimeSettings(cleared);
        assertTrue(BridgeConfig.isRuntimeLoaded());
        assertFalse(BridgeConfig.hasRuntimeSession());

        Properties subsequent = ConfigFileClient.readOrWrite(context, true, true, Collections.emptyMap());
        assertTrue(subsequent.isEmpty());
        assertFalse(directory.exists());
        BridgeConfig.acceptRuntimeSettings(subsequent);
        assertTrue(BridgeConfig.isRuntimeLoaded());
        assertFalse(BridgeConfig.hasRuntimeSession());
    }

    @Test public void deleteRejectsUnexpectedChildrenWithoutDeletingAnyFile() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        byte[] original = Files.readAllBytes(main.toPath());
        File unexpected = new File(directory, "not-owned-by-observatory");
        Files.write(unexpected.toPath(), new byte[]{9});
        assertThrows(IOException.class, () -> WeChatConfigStore.delete(context));
        assertArrayEquals(original, Files.readAllBytes(main.toPath()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(unexpected.toPath()));
    }

    @Test public void contextPackageAndProcessUidAreCheckedBeforeAnyFilesystemWrite() throws Exception {
        HostContext module = new HostContext(data);
        module.packageName = ConfigWriteContract.MODULE_PACKAGE;
        assertAllOperationsRejected(module);
        HostContext foreignUid = new HostContext(data);
        foreignUid.info.uid = android.os.Process.myUid() + 1;
        assertAllOperationsRejected(foreignUid);
        assertEquals(0, module.noBackupCalls);
        assertEquals(0, foreignUid.noBackupCalls);
        assertFalse(root.exists());
    }

    @Test public void redirectedContextRootIsRejected() throws Exception {
        assertTrue(root.mkdir());
        context.overrideRoot = temporary.newFolder("foreign-no-backup");
        assertAllOperationsRejected(context);
        assertArrayEquals(new String[0], context.overrideRoot.list());
        assertFalse(directory.exists());
    }

    @Test public void symbolicLinkDirectoryIsRejectedWithoutFollowingIt() throws Exception {
        assertTrue(root.mkdir());
        File outside = temporary.newFolder("unrelated");
        File sentinel = new File(outside, WeChatConfigStore.MAIN);
        Files.write(sentinel.toPath(), new byte[]{2, 3, 4});
        Files.createSymbolicLink(directory.toPath(), outside.toPath());
        assertAllOperationsRejected(context);
        assertArrayEquals(new byte[]{2, 3, 4}, Files.readAllBytes(sentinel.toPath()));
        assertTrue(Files.isSymbolicLink(directory.toPath()));
    }

    @Test public void symbolicLinksAtEachOwnedFileAreRejectedWithoutFollowingThem() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        byte[] original = Files.readAllBytes(main.toPath());
        File outside = temporary.newFile("outside-secret");
        Files.write(outside.toPath(), new byte[]{1, 2, 3});
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File link = new File(main + suffix);
            if (link.exists()) assertTrue(link.delete());
            Files.createSymbolicLink(link.toPath(), outside.toPath());
            assertAllOperationsRejected(context);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(outside.toPath()));
            Files.delete(link.toPath());
            Files.write(main.toPath(), original);
        }
    }

    @Test public void invalidInputsAreRejectedBeforeCreatingDirectory() throws Exception {
        Properties unknown = business();
        unknown.setProperty("self_wxid", "must-never-be-configured");
        Properties wrongType = business();
        wrongType.put("api_key", 123);
        Properties badUrl = business();
        badUrl.setProperty("bridge_url", "file:///private/file");
        Properties oversized = business();
        oversized.setProperty("api_key", repeated('x', 4097));
        Properties badSwitch = business();
        badSwitch.setProperty("enabled", "maybe");
        for (Properties invalid : Arrays.asList(unknown, wrongType, badUrl, oversized, badSwitch)) {
            assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(context, invalid, true));
            assertFalse(root.exists());
        }
    }

    @Test public void interruptedAtomicWriteRecoversLastCommittedRangeBeforeReservingAgain() throws Exception {
        Properties original = WeChatConfigStore.saveAndLoad(context, business(), true);
        // Closing without finishWrite/failWrite simulates process termination:
        // Android 23 leaves main + .bak; Android 35 leaves main + .new.
        AtomicFile atomic = new AtomicFile(main);
        try (FileOutputStream interrupted = atomic.startWrite()) {
            interrupted.write("invalid-partial-transaction".getBytes(StandardCharsets.UTF_8));
            interrupted.flush();
            interrupted.getFD().sync();
        }
        Properties afterCrash = WeChatConfigStore.loadForRuntime(new HostContext(data));
        assertRange(afterCrash, 1_000_001, 2_000_000);
        assertEquals(original.getProperty(WeChatConfigStore.INSTALLATION),
                afterCrash.getProperty(WeChatConfigStore.INSTALLATION));
        assertEquals("dummy-key", afterCrash.getProperty("api_key"));
        assertEquals("2000000", diskState().getProperty(WeChatConfigStore.HIGH_WATERMARK));
        assertFalse(new File(main + ".bak").exists());
        assertFalse(new File(main + ".new").exists());
    }

    @Test public void corruptCommittedFileIsNeverSilentlyReplacedBySave() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        byte[] invalid = "api_key=a\napi_key=b\n".getBytes(StandardCharsets.UTF_8);
        Files.write(main.toPath(), invalid);
        assertThrows(IOException.class, () -> WeChatConfigStore.read(context));
        assertThrows(IOException.class, () -> WeChatConfigStore.loadForRuntime(context));
        assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(context, business(), true));
        assertArrayEquals(invalid, Files.readAllBytes(main.toPath()));
        WeChatConfigStore.delete(context);
        assertFalse(directory.exists());
    }

    @Test public void missingMetadataCannotResetInstallationOrHighWatermark() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        Properties committed = diskState();
        for (String metadata : Arrays.asList(WeChatConfigStore.INSTALLATION,
                WeChatConfigStore.HIGH_WATERMARK, WeChatConfigStore.REVISION)) {
            Properties damaged = new Properties();
            damaged.putAll(committed);
            damaged.remove(metadata);
            writeDiskState(damaged);
            byte[] before = Files.readAllBytes(main.toPath());
            assertThrows(IOException.class, () -> WeChatConfigStore.read(context));
            assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(context, business(), true));
            assertArrayEquals(before, Files.readAllBytes(main.toPath()));
        }
    }

    @Test public void corruptBackupDoesNotReplaceGoodCommittedFile() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        byte[] before = Files.readAllBytes(main.toPath());
        byte[] damaged = "api_key=broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(main + ".bak").toPath(), damaged);
        assertThrows(IOException.class, () -> WeChatConfigStore.read(context));
        assertArrayEquals(before, Files.readAllBytes(main.toPath()));
        assertArrayEquals(damaged, Files.readAllBytes(new File(main + ".bak").toPath()));
    }

    @Test public void oversizedDiskFileIsRejectedWithoutReplacement() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        byte[] oversized = new byte[WeChatConfigStore.MAX_BYTES + 1];
        Arrays.fill(oversized, (byte) 'x');
        Files.write(main.toPath(), oversized);
        assertThrows(IOException.class, () -> WeChatConfigStore.read(context));
        assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(context, business(), true));
        assertArrayEquals(oversized, Files.readAllBytes(main.toPath()));
    }

    @Test public void exhaustedGenerationRangeFailsWithoutModifyingCommittedState() throws Exception {
        WeChatConfigStore.saveAndLoad(context, business(), true);
        Properties exhausted = diskState();
        exhausted.setProperty(WeChatConfigStore.HIGH_WATERMARK, Long.toString(Long.MAX_VALUE - 10));
        writeDiskState(exhausted);
        byte[] before = Files.readAllBytes(main.toPath());
        assertThrows(IOException.class, () -> WeChatConfigStore.loadForRuntime(context));
        assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(context, business(), true));
        assertArrayEquals(before, Files.readAllBytes(main.toPath()));
    }

    private void assertAllOperationsRejected(HostContext rejected) {
        assertThrows(IOException.class, () -> WeChatConfigStore.read(rejected));
        assertThrows(IOException.class, () -> WeChatConfigStore.loadForRuntime(rejected));
        assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(rejected, business(), true));
        assertThrows(IOException.class, () -> WeChatConfigStore.saveAndLoad(rejected, new Properties(), false));
        assertThrows(IOException.class, () -> WeChatConfigStore.delete(rejected));
    }

    private Properties diskState() throws IOException {
        Properties state = new Properties();
        try (FileInputStream input = new FileInputStream(main)) { state.load(input); }
        return state;
    }

    private void writeDiskState(Properties state) throws IOException {
        try (FileOutputStream output = new FileOutputStream(main)) { state.store(output, "test fixture"); }
    }

    private static Properties business() {
        Properties properties = new Properties();
        properties.setProperty("bridge_url", "https://gateway.example.invalid");
        properties.setProperty("api_key", "dummy-key");
        return properties;
    }

    private static void assertRange(Properties snapshot, long first, long last) {
        assertEquals(Long.toString(first), snapshot.getProperty("__generation_start"));
        assertEquals(Long.toString(last), snapshot.getProperty("__generation_limit"));
    }

    private static String repeated(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }

    /** Simulate only Context paths; filesystem operations and AtomicFile remain real. */
    private static final class HostContext extends ContextWrapper {
        final File data;
        final ApplicationInfo info = new ApplicationInfo();
        String packageName = "com.tencent.mm";
        File overrideRoot;
        int noBackupCalls;

        HostContext(File data) {
            super(RuntimeEnvironment.getApplication());
            this.data = data;
            info.dataDir = data.getAbsolutePath();
            info.packageName = packageName;
            info.uid = android.os.Process.myUid();
        }

        @Override public String getPackageName() { return packageName; }
        @Override public ApplicationInfo getApplicationInfo() { return info; }
        @Override public File getNoBackupFilesDir() {
            noBackupCalls++;
            File result = overrideRoot == null ? new File(data, "no_backup") : overrideRoot;
            if (!result.exists() && !result.mkdir()) throw new AssertionError("Cannot create test no_backup");
            return result;
        }
        @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
            throw new AssertionError("Private configuration must not bind the module process");
        }
        @Override public void startActivity(Intent intent) {
            throw new AssertionError("Private configuration must not launch the module");
        }
    }
}
