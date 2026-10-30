package com.anopticlabs.gravel.notebook.backup;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/*
 * A file-backed {@link DocumentsProvider} for the instrumentation APK, declared in its own
 * manifest and never shipped, so {@code BackupDocumentsContractTest} drives the production SAF
 * binding through the real framework plumbing. It runs in the test package's own process and
 * UID, which loads only the test APK's classes, so it is plain Java over {@code android.*} and
 * {@code java.*}. Document ids are {@code root/<name>}. The open mode is honored literally: a
 * plain {@code "w"} does not truncate, so only the production {@code "wt"} leaves no tail behind a
 * shorter write. The root also serves as a granted folder ({@link #treeUri()}), with renames
 * and the child check tree URIs need. The misbehaviors a test can switch on through
 * {@code test.config}: reads that serve other bytes than were written, refused writes, and an
 * unreported size.
 */
public class TestDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.anopticlabs.gravel.test.documents";
    private static final String ROOT_ID = "root";

    public static final String METHOD_RESET = "test.reset";
    public static final String METHOD_CONFIG = "test.config";
    public static final String METHOD_CREATE = "test.create";
    public static final String METHOD_LIST = "test.list";
    public static final String METHOD_READ = "test.read";
    public static final String METHOD_DELETE = "test.delete";
    public static final String EXTRA_CORRUPT_READS = "corruptReads";
    public static final String EXTRA_REFUSE_WRITE = "refuseWrite";
    public static final String EXTRA_REPORT_SIZE = "reportSize";
    public static final String EXTRA_BYTES = "bytes";
    public static final String EXTRA_NAMES = "names";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    };

    public static Uri rootUri() {
        return DocumentsContract.buildDocumentUri(AUTHORITY, ROOT_ID);
    }

    public static Uri documentUri(String name) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, ROOT_ID + "/" + name);
    }

    public static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    private volatile boolean corruptReads = false;
    private volatile boolean refuseWrite = false;
    private volatile boolean reportSize = true;

    private File rootDir() {
        File dir = new File(getContext().getCacheDir(), "test-documents-root");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(String documentId) {
        if (ROOT_ID.equals(documentId)) return rootDir();
        String rel = documentId.startsWith(ROOT_ID + "/") ? documentId.substring(ROOT_ID.length() + 1) : documentId;
        if (rel.isEmpty() || rel.contains("..") || rel.contains("/")) {
            throw new IllegalArgumentException("bad document id " + documentId);
        }
        return new File(rootDir(), rel);
    }

    private String idFor(File file) {
        return file.equals(rootDir()) ? ROOT_ID : ROOT_ID + "/" + file.getName();
    }

    private void addFile(MatrixCursor cursor, File file) throws FileNotFoundException {
        if (!file.exists()) throw new FileNotFoundException(file.getPath());
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, idFor(file));
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        int flags = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        if (file.isDirectory()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, reportSize ? (Object) file.length() : null);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, "test root");
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addFile(cursor, fileFor(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File[] children = fileFor(parentDocumentId).listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) addFile(cursor, child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = fileFor(documentId);
        if (!file.exists()) throw new FileNotFoundException(file.getPath());
        int accessMode = "w".equals(mode)
                ? (ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE)
                : ParcelFileDescriptor.parseMode(mode);
        if (refuseWrite && (accessMode & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
            throw new FileNotFoundException("write refused by test provider");
        }
        if (corruptReads && accessMode == ParcelFileDescriptor.MODE_READ_ONLY) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length > 0) bytes[0] = (byte) (bytes[0] ^ 0xFF);
                File shadow = new File(rootDir(), ".shadow-" + file.getName());
                Files.write(shadow.toPath(), bytes);
                return ParcelFileDescriptor.open(shadow, accessMode);
            } catch (IOException e) {
                throw new FileNotFoundException("shadow failed: " + e);
            }
        }
        return ParcelFileDescriptor.open(file, accessMode);
    }

    private static String validName(String displayName) {
        if (displayName == null || displayName.isEmpty() || displayName.contains("/") || displayName.contains("..")) {
            throw new IllegalArgumentException("bad display name " + displayName);
        }
        return displayName;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File file = new File(fileFor(parentDocumentId), validName(displayName));
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                //noinspection ResultOfMethodCallIgnored
                file.mkdirs();
            } else {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            }
        } catch (IOException e) {
            throw new FileNotFoundException("create failed: " + e);
        }
        return idFor(file);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && documentId.startsWith(ROOT_ID + "/");
    }

    /** The id is the name, so a rename always returns a new id, as ExternalStorageProvider's does. */
    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File before = fileFor(documentId);
        if (!before.exists()) throw new FileNotFoundException(before.getPath());
        File after = new File(rootDir(), validName(displayName));
        if (after.exists()) throw new IllegalStateException("already exists: " + displayName);
        if (!before.renameTo(after)) throw new IllegalStateException("rename failed");
        return idFor(after);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (!file.exists()) throw new FileNotFoundException(file.getPath());
        deleteRecursively(file);
        if (file.exists()) throw new FileNotFoundException("delete failed: " + file.getPath());
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /*
     * The {@code test.*} control surface bypasses the per-document permission checks, so the
     * manifest's {@code MANAGE_DOCUMENTS} gate and the root grant are all that stand between
     * another app and it; keep the manifest gate.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case METHOD_RESET: {
                deleteRecursively(rootDir());
                //noinspection ResultOfMethodCallIgnored
                rootDir().mkdirs();
                corruptReads = false;
                refuseWrite = false;
                reportSize = true;
                return new Bundle();
            }
            case METHOD_CONFIG: {
                corruptReads = extras.getBoolean(EXTRA_CORRUPT_READS, false);
                refuseWrite = extras.getBoolean(EXTRA_REFUSE_WRITE, false);
                reportSize = extras.getBoolean(EXTRA_REPORT_SIZE, true);
                return new Bundle();
            }
            case METHOD_CREATE: {
                byte[] bytes = extras != null ? extras.getByteArray(EXTRA_BYTES) : null;
                try {
                    File target = new File(rootDir(), validName(arg));
                    Files.write(target.toPath(), bytes != null ? bytes : new byte[0]);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new Bundle();
            }
            case METHOD_DELETE: {
                deleteRecursively(new File(rootDir(), validName(arg)));
                return new Bundle();
            }
            case METHOD_LIST: {
                String[] all = rootDir().list();
                List<String> names = new ArrayList<>();
                if (all != null) for (String n : all) if (!n.startsWith(".shadow-")) names.add(n);
                Collections.sort(names);
                Bundle out = new Bundle();
                out.putStringArrayList(EXTRA_NAMES, new ArrayList<>(names));
                return out;
            }
            case METHOD_READ: {
                try {
                    Bundle out = new Bundle();
                    out.putByteArray(EXTRA_BYTES, Files.readAllBytes(new File(rootDir(), validName(arg)).toPath()));
                    return out;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            default:
                return super.call(method, arg, extras);
        }
    }
}
