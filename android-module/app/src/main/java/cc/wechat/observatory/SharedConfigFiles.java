package cc.wechat.observatory;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/** Local public files, opened under the caller's own persisted system folder grant. */
abstract class SharedConfigFiles implements Closeable {
    static final String AUTHORITY = "com.android.externalstorage.documents";
    static final String PARENT_ID = "primary:Documents";
    static final String DIRECTORY_ID = PARENT_ID + "/WeChatObservatory";
    static final String DISPLAY_PATH = "Documents/WeChatObservatory/observatory.properties";
    private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    static boolean acceptedTree(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || !DocumentsContract.isTreeUri(uri)) return false;
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            return PARENT_ID.equals(id) || DIRECTORY_ID.equals(id);
        } catch (RuntimeException invalid) { return false; }
    }

    private static Uri grantedTree(Context context) {
        Uri selected = null;
        long newest = Long.MIN_VALUE;
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                Uri uri = permission.getUri();
                if (!permission.isReadPermission() || !permission.isWritePermission() || !acceptedTree(uri)) continue;
                if (permission.getPersistedTime() >= newest) {
                    selected = uri;
                    newest = permission.getPersistedTime();
                }
            }
        } catch (RuntimeException unavailable) { return null; }
        return selected;
    }

    static boolean hasPermission(Context context) { return grantedTree(context) != null; }

    static Intent permissionIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(RW | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(AUTHORITY, PARENT_ID));
        }
        return intent;
    }

    static void acceptPermission(Context context, Intent result) throws IOException {
        Uri uri = result == null ? null : result.getData();
        if (!acceptedTree(uri)) throw new IOException("请选择本机存储的 Documents 或其中的 WeChatObservatory 文件夹。");
        if ((result.getFlags() & RW) != RW) throw new IOException("需要此文件夹的读取和写入授权。");
        try {
            context.getContentResolver().takePersistableUriPermission(uri, RW);
            // Confirm the grant and directory before allowing an editor to save.
            try (SharedConfigFiles ignored = open(context)) { }
        } catch (SecurityException failure) {
            throw new IOException("目录授权未保存，请重新选择文件夹。", failure);
        }
    }

    static SharedConfigFiles open(Context context) throws IOException {
        Uri tree = grantedTree(context);
        if (tree == null) throw new IOException("请先授权 Documents/WeChatObservatory 配置目录。");
        try { return new DocumentsFiles(context.getContentResolver(), tree); }
        catch (RuntimeException failure) { throw new IOException("配置目录不可访问，请重新授权。", failure); }
    }

    abstract Closeable lock(long deadlineElapsedMs) throws IOException;
    abstract boolean exists(String name) throws IOException;
    abstract byte[] read(String name, int maxBytes) throws IOException;
    abstract void writeSync(String name, byte[] bytes) throws IOException;
    abstract void rename(String from, String to) throws IOException;
    abstract void delete(String name) throws IOException;
    @Override public void close() throws IOException { }

    private static final class DocumentsFiles extends SharedConfigFiles {
        private final ContentResolver resolver;
        private final Uri tree;

        DocumentsFiles(ContentResolver resolver, Uri tree) throws IOException {
            this.resolver = resolver;
            this.tree = tree;
            if (PARENT_ID.equals(DocumentsContract.getTreeDocumentId(tree))) {
                ensure(PARENT_ID, "WeChatObservatory", DocumentsContract.Document.MIME_TYPE_DIR);
            } else if (!existsId(DIRECTORY_ID, DocumentsContract.Document.MIME_TYPE_DIR)) {
                throw new IOException("配置文件夹已删除，请重新选择 Documents 文件夹。");
            }
        }

        private Uri document(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

        private String id(String name) throws IOException {
            if (!(".lock".equals(name) || "observatory.properties".equals(name)
                    || "observatory.properties.new".equals(name) || "observatory.properties.bak".equals(name))) {
                throw new IOException("Unsupported configuration filename");
            }
            return DIRECTORY_ID + "/" + name;
        }

        private boolean existsId(String id, String expectedMime) throws IOException {
            // Query children of an existing directory: querying a missing document
            // returns null/FileNotFoundException on Android's local provider.
            boolean grantRoot = id.equals(DocumentsContract.getTreeDocumentId(tree));
            Uri query = grantRoot ? document(id) : DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, id.substring(0, id.lastIndexOf('/')));
            try (Cursor cursor = resolver.query(query, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor == null) throw new IOException("Configuration directory unavailable");
                while (cursor.moveToNext()) {
                    if (!id.equals(cursor.getString(0))) continue;
                    boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1));
                    if (directory != DocumentsContract.Document.MIME_TYPE_DIR.equals(expectedMime)) {
                        throw new IOException("Unexpected configuration file type");
                    }
                    return true;
                }
                return false;
            } catch (RuntimeException failure) {
                throw new IOException("Configuration directory query failed", failure);
            }
        }

        private Uri ensure(String parent, String name, String mime) throws IOException {
            String expected = parent + "/" + name;
            if (existsId(expected, mime)) return document(expected);
            Uri created;
            try { created = DocumentsContract.createDocument(resolver, document(parent), mime, name); }
            catch (RuntimeException failure) { throw new IOException("Cannot create configuration file", failure); }
            if (created == null) throw new IOException("Cannot create configuration file");
            // Two UIDs may create simultaneously. Always reopen the canonical file,
            // especially .lock; never use the provider's disambiguated '(1)' file.
            if (!expected.equals(DocumentsContract.getDocumentId(created))) {
                try { DocumentsContract.deleteDocument(resolver, created); }
                catch (IOException | RuntimeException ignored) { }
            }
            if (!existsId(expected, mime)) throw new IOException("Canonical configuration file unavailable");
            return document(expected);
        }

        @Override boolean exists(String name) throws IOException { return existsId(id(name), "application/octet-stream"); }

        @Override Closeable lock(long deadlineElapsedMs) throws IOException {
            Uri uri = ensure(DIRECTORY_ID, ".lock", "application/octet-stream");
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw");
            if (descriptor == null) throw new IOException("Cannot open configuration lock");
            ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
            boolean acquired = false;
            try {
                while (SystemClock.elapsedRealtime() < deadlineElapsedMs) {
                    FileLock held = null;
                    try { held = stream.getChannel().tryLock(); }
                    catch (OverlappingFileLockException busy) { }
                    if (held != null) {
                        acquired = true;
                        final FileLock lock = held;
                        return () -> { try { lock.release(); } finally { stream.close(); } };
                    }
                    try { Thread.sleep(25L); }
                    catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Configuration operation cancelled", cancelled);
                    }
                }
                throw new IOException("Configuration is busy; please retry");
            } finally { if (!acquired) stream.close(); }
        }

        @Override byte[] read(String name, int maxBytes) throws IOException {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(document(id(name)), "r");
            if (descriptor == null) throw new IOException("Cannot read configuration file");
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > maxBytes) throw new IOException("Configuration file exceeds size limit");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            }
        }

        @Override void writeSync(String name, byte[] bytes) throws IOException {
            id(name);
            Uri uri = ensure(DIRECTORY_ID, name, "application/octet-stream");
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rwt");
            if (descriptor == null) throw new IOException("Cannot write configuration file");
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
        }

        @Override void rename(String from, String to) throws IOException {
            String destination = id(to);
            if (exists(to)) throw new IOException("Configuration rename destination exists");
            Uri renamed = DocumentsContract.renameDocument(resolver, document(id(from)), to);
            if (renamed == null || !destination.equals(DocumentsContract.getDocumentId(renamed))
                    || !exists(to) || exists(from)) throw new IOException("Configuration rename failed");
        }

        @Override void delete(String name) throws IOException {
            if (exists(name) && (!DocumentsContract.deleteDocument(resolver, document(id(name))) || exists(name))) {
                throw new IOException("Configuration delete failed");
            }
        }
    }
}
