package cc.wechat.observatory;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Exercises the production SAF backend, including provider behavior on absent document IDs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, manifest = Config.NONE)
public class SharedConfigFilesTest {
    private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final String FILE = "observatory.properties";
    private static final String NEW = FILE + ".new";
    private static final String BACKUP = FILE + ".bak";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private ContentResolver resolver;
    private LocalDocuments provider;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        resolver = context.getContentResolver();
        provider = new LocalDocuments(temporary.newFolder("Documents"));
        ProviderInfo info = new ProviderInfo();
        info.authority = SharedConfigFiles.AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
    }

    @Test public void freshFolderGrantCreatesFilesWithoutQueryingMissingDocument() throws Exception {
        Uri parent = tree(SharedConfigFiles.PARENT_ID);
        // Android's local provider does not return an empty cursor for this query.
        assertNull(resolver.query(DocumentsContract.buildDocumentUriUsingTree(parent,
                SharedConfigFiles.DIRECTORY_ID + "/" + FILE), null, null, null, null));
        provider.missingDocumentQueries = 0;
        SharedConfigFiles.acceptPermission(context, result(parent, RW));
        assertTrue(SharedConfigFiles.hasPermission(context));
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            assertFalse(files.exists(FILE));
            files.writeSync(NEW, bytes("api_key=dummy\n"));
            files.rename(NEW, FILE);
            assertArrayEquals(bytes("api_key=dummy\n"), files.read(FILE, 1024));
            assertFalse(files.exists(NEW));
            files.delete(FILE);
            assertFalse(files.exists(FILE));
        }
        assertEquals("absence must be determined from the parent listing", 0, provider.missingDocumentQueries);
        assertTrue(provider.childQueries > 0);
        assertEquals(1, resolver.getPersistedUriPermissions().size());
    }

    @Test public void persistedReadOnlyGrantCannotOpenOrSave() throws Exception {
        resolver.takePersistableUriPermission(tree(SharedConfigFiles.PARENT_ID), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertFalse(SharedConfigFiles.hasPermission(context));
        expectIo(() -> { try (SharedConfigFiles ignored = SharedConfigFiles.open(context)) { } });
        assertEquals(0, provider.created.size());
    }

    @Test public void rejectedPickerResultDoesNotPersistPartialOrForeignGrant() throws Exception {
        expectIo(() -> SharedConfigFiles.acceptPermission(context,
                result(tree(SharedConfigFiles.PARENT_ID), Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        expectIo(() -> SharedConfigFiles.acceptPermission(context,
                result(tree("primary:Download"), RW)));
        expectIo(() -> SharedConfigFiles.acceptPermission(context,
                result(DocumentsContract.buildTreeDocumentUri("untrusted.example", SharedConfigFiles.DIRECTORY_ID), RW)));
        assertTrue(resolver.getPersistedUriPermissions().isEmpty());
        assertTrue(provider.created.isEmpty());
    }

    @Test public void newerParentGrantRecoversDeletedPreviouslyGrantedChild() throws Exception {
        addGrant(tree(SharedConfigFiles.DIRECTORY_ID), 1L);
        addGrant(tree(SharedConfigFiles.PARENT_ID), 2L);
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            files.writeSync(FILE, bytes("api_key=dummy\n"));
            assertTrue(files.exists(FILE));
        }
        assertTrue(provider.file(SharedConfigFiles.DIRECTORY_ID).isDirectory());
        assertEquals(0, provider.missingDocumentQueries);
    }

    @Test public void existingDestinationIsNeverSilentlyRenamedToNumberedSibling() throws Exception {
        grantParent();
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            files.writeSync(FILE, bytes("old"));
            files.writeSync(NEW, bytes("new"));
            expectIo(() -> files.rename(NEW, FILE));
            assertArrayEquals(bytes("old"), files.read(FILE, 100));
            assertArrayEquals(bytes("new"), files.read(NEW, 100));
            assertEquals(0, provider.renameCalls);
            files.rename(FILE, BACKUP);
            files.rename(NEW, FILE);
            assertArrayEquals(bytes("old"), files.read(BACKUP, 100));
            assertArrayEquals(bytes("new"), files.read(FILE, 100));
        }
    }

    @Test public void providerDisambiguationDuringRenameIsReportedAsFailure() throws Exception {
        grantParent();
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            files.writeSync(NEW, bytes("candidate"));
            provider.raceNextRename = true;
            expectIo(() -> files.rename(NEW, FILE));
            assertTrue("unexpected provider name must never count as a committed main file",
                    provider.file(SharedConfigFiles.DIRECTORY_ID + "/" + FILE + " (1)").exists());
        }
    }

    @Test public void competingLockCreationAlwaysOpensCanonicalInode() throws Exception {
        grantParent();
        provider.raceLockCreation = true;
        try (SharedConfigFiles first = SharedConfigFiles.open(context);
             SharedConfigFiles second = SharedConfigFiles.open(context);
             Closeable held = first.lock(SystemClock.elapsedRealtime() + 10000L)) {
            assertEquals(SharedConfigFiles.DIRECTORY_ID + "/.lock", provider.lastOpenedId);
            assertTrue(provider.file(SharedConfigFiles.DIRECTORY_ID + "/.lock").exists());
            assertFalse(provider.file(SharedConfigFiles.DIRECTORY_ID + "/.lock (1)").exists());
            // A second handle to the same JVM inode must overlap. Interrupt the retry wait
            // rather than relying on wall time to advance Robolectric's virtual clock.
            Thread.currentThread().interrupt();
            try { expectIo(() -> { try (Closeable ignored = second.lock(SystemClock.elapsedRealtime() + 10000L)) { } }); }
            finally { Thread.interrupted(); }
        }
        try (SharedConfigFiles next = SharedConfigFiles.open(context);
             Closeable reacquired = next.lock(SystemClock.elapsedRealtime() + 10000L)) {
            assertNotNull(reacquired);
        }
    }

    @Test public void folderInsteadOfConfigAndOversizedReadsFailClosed() throws Exception {
        grantParent();
        try (SharedConfigFiles files = SharedConfigFiles.open(context)) {
            assertTrue(provider.file(SharedConfigFiles.DIRECTORY_ID + "/" + FILE).mkdir());
            expectIo(() -> files.exists(FILE));
            assertTrue(provider.file(SharedConfigFiles.DIRECTORY_ID + "/" + FILE).delete());
            files.writeSync(FILE, bytes("12345"));
            expectIo(() -> files.read(FILE, 4));
        }
    }

    private void grantParent() throws IOException {
        SharedConfigFiles.acceptPermission(context, result(tree(SharedConfigFiles.PARENT_ID), RW));
    }

    private void addGrant(Uri uri, long time) {
        UriPermission permission = ReflectionHelpers.callConstructor(UriPermission.class,
                ReflectionHelpers.ClassParameter.from(Uri.class, uri),
                ReflectionHelpers.ClassParameter.from(int.class, RW),
                ReflectionHelpers.ClassParameter.from(long.class, time));
        // Robolectric exposes the stored permission list; no app-private URI cache is involved.
        resolver.getPersistedUriPermissions().add(permission);
    }

    private static Uri tree(String id) { return DocumentsContract.buildTreeDocumentUri(SharedConfigFiles.AUTHORITY, id); }
    private static Intent result(Uri uri, int flags) { return new Intent().setData(uri).addFlags(flags); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private interface IoAction { void run() throws IOException; }
    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); fail("Expected a storage failure"); }
        catch (IOException expected) { }
    }

    /** Models ExternalStorageProvider's path IDs, absent-query null, and unique-name mutations. */
    private static final class LocalDocuments extends ContentProvider {
        final File documents;
        final List<String> created = new ArrayList<>();
        int missingDocumentQueries;
        int childQueries;
        int renameCalls;
        boolean raceLockCreation;
        boolean raceNextRename;
        String lastOpenedId;

        LocalDocuments(File documents) { this.documents = documents; }
        @Override public boolean onCreate() { return true; }
        File file(String id) {
            if (SharedConfigFiles.PARENT_ID.equals(id)) return documents;
            if (!id.startsWith(SharedConfigFiles.PARENT_ID + "/")) throw new IllegalArgumentException("outside Documents");
            return new File(documents, id.substring(SharedConfigFiles.PARENT_ID.length() + 1));
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String id = DocumentsContract.getDocumentId(uri);
            File selected = file(id);
            if (!selected.exists()) {
                missingDocumentQueries++;
                return null;
            }
            String[] columns = projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            if (uri.getPathSegments().contains("children")) {
                childQueries++;
                File[] children = selected.listFiles();
                if (children != null) for (File child : children) addRow(result, id + "/" + child.getName(), child);
            } else addRow(result, id, selected);
            return result;
        }

        private static void addRow(MatrixCursor cursor, String id, File file) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) row.add(id);
                else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) row.add(file.isDirectory()
                        ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream");
                else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) row.add(file.getName());
                else row.add(null);
            }
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            Uri source = extras.getParcelable("uri");
            String id = DocumentsContract.getDocumentId(source);
            Bundle result = new Bundle();
            try {
                if ("android:createDocument".equals(method)) {
                    String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    String mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    File parent = file(id);
                    if (raceLockCreation && ".lock".equals(name)) {
                        raceLockCreation = false;
                        if (!new File(parent, name).createNewFile()) throw new IOException();
                    }
                    File child = unique(parent, name);
                    boolean made = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? child.mkdir() : child.createNewFile();
                    if (!made) throw new IOException();
                    String childId = id + "/" + child.getName();
                    created.add(childId);
                    result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(source, childId));
                } else if ("android:renameDocument".equals(method)) {
                    renameCalls++;
                    String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    File before = file(id);
                    if (raceNextRename) {
                        raceNextRename = false;
                        if (!new File(before.getParentFile(), name).createNewFile()) throw new IOException();
                    }
                    File after = unique(before.getParentFile(), name);
                    if (!before.renameTo(after)) throw new IOException();
                    String afterId = id.substring(0, id.lastIndexOf('/') + 1) + after.getName();
                    result.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(source, afterId));
                } else if ("android:deleteDocument".equals(method)) {
                    File target = file(id);
                    if (target.exists() && !target.delete()) throw new IOException();
                } else throw new UnsupportedOperationException(method);
                return result;
            } catch (IOException failure) { throw new IllegalStateException("fake provider operation failed", failure); }
        }

        private static File unique(File parent, String name) {
            File next = new File(parent, name);
            for (int suffix = 1; next.exists(); suffix++) next = new File(parent, name + " (" + suffix + ")");
            return next;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            lastOpenedId = DocumentsContract.getDocumentId(uri);
            return ParcelFileDescriptor.open(file(lastOpenedId), ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
