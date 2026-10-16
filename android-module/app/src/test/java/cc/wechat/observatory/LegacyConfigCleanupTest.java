package cc.wechat.observatory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.*;

public class LegacyConfigCleanupTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private final LegacyConfigCleanup.FileAccess files = file -> {
        if (file.isDirectory()) throw new IOException("refuse directory");
        Files.deleteIfExists(file.toPath());
    };

    @Test public void removesOnlyNamedFileAndPreservesNeighborAndDirectory() throws Exception {
        File base = temporary.newFolder();
        File old = new File(base, "config.properties");
        File neighbor = new File(base, "other.properties");
        Files.write(old.toPath(), new byte[]{1});
        Files.write(neighbor.toPath(), new byte[]{2});

        assertEquals(LegacyConfigCleanup.Removal.DONE,
                LegacyConfigCleanup.remove(target(base), files));
        assertFalse(old.exists());
        assertTrue(neighbor.isFile());
        assertTrue(base.isDirectory());
    }

    @Test public void distinguishesMissingFileFromDeniedTraversal() throws Exception {
        File base = temporary.newFolder();
        assertEquals(LegacyConfigCleanup.Removal.DONE,
                LegacyConfigCleanup.remove(target(base), files));
        assertEquals(LegacyConfigCleanup.Removal.NEEDS_ACCESS,
                LegacyConfigCleanup.remove(target(base), file -> { throw new IOException("denied"); }));
    }

    @Test public void refusesSymlinkedChildDirectoryWithoutTouchingDestination() throws Exception {
        File base = temporary.newFolder();
        File unrelated = temporary.newFolder();
        File secret = new File(unrelated, "config.properties");
        Files.write(secret.toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(base, "old").toPath(), unrelated.toPath());

        LegacyConfigCleanup.Target target = new LegacyConfigCleanup.Target(
                base, "old/config.properties", "test");
        assertEquals(LegacyConfigCleanup.Removal.REFUSED_PATH,
                LegacyConfigCleanup.remove(target, files));
        assertTrue(secret.isFile());
        try {
            LegacyConfigCleanup.rootCommand(Collections.singletonList(target));
            fail("changed paths must not be retried with root");
        } catch (IOException expected) { }
    }

    @Test public void refusesSymlinkedFileAndDirectoryAtExpectedFileName() throws Exception {
        File base = temporary.newFolder();
        File unrelated = temporary.newFile();
        File old = new File(base, "config.properties");
        Files.createSymbolicLink(old.toPath(), unrelated.toPath());
        assertEquals(LegacyConfigCleanup.Removal.REFUSED_PATH,
                LegacyConfigCleanup.remove(target(base), files));
        assertTrue(unrelated.isFile());
        Files.delete(old.toPath());
        assertTrue(old.mkdir());
        assertEquals(LegacyConfigCleanup.Removal.NEEDS_ACCESS,
                LegacyConfigCleanup.remove(target(base), files));
        assertTrue(old.isDirectory());
    }

    @Test public void privilegedCommandQuotesPathsAndDoesNotRemoveNeighbors() throws Exception {
        File base = temporary.newFolder("owner ' quote $(no-execution)");
        File old = new File(base, "config.properties");
        File neighbor = new File(base, "preserved");
        Files.write(old.toPath(), "dummy".getBytes(StandardCharsets.UTF_8));
        Files.write(neighbor.toPath(), new byte[]{1});
        String command = LegacyConfigCleanup.rootCommand(Collections.singletonList(target(base)));

        assertEquals(0, new ProcessBuilder("sh", "-c", command).start().waitFor());
        assertFalse(old.exists());
        assertTrue(neighbor.isFile());
        assertTrue(base.isDirectory());
    }

    @Test public void privilegedCommandDoesNotReportSuccessForADirectory() throws Exception {
        File base = temporary.newFolder();
        File old = new File(base, "config.properties");
        assertTrue(old.mkdir());
        String command = LegacyConfigCleanup.rootCommand(Collections.singletonList(target(base)));

        assertNotEquals(0, new ProcessBuilder("sh", "-c", command).start().waitFor());
        assertTrue(old.isDirectory());
    }

    private static LegacyConfigCleanup.Target target(File base) {
        return new LegacyConfigCleanup.Target(base, "config.properties", "test");
    }
}
