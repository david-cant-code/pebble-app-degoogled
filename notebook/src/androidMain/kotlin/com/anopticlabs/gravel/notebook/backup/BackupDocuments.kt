package com.anopticlabs.gravel.notebook.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [BackupDocumentOps] over the Storage Access Framework, with nothing but the picker's grant on the document. */
class BackupDocuments(private val resolver: ContentResolver) : BackupDocumentOps<Uri> {

    override fun describe(handle: Uri): BackupDocumentInfo? = try {
        resolver.query(handle, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx).takeIf { it >= 0 } else null
            if (name.isNullOrEmpty()) null else BackupDocumentInfo(name, size)
        }
    } catch (_: Exception) {
        null
    }

    /** `wt`: a plain `w` is not guaranteed to truncate on every provider, and a stale tail fails the archive's tag. */
    @Throws(IOException::class)
    override fun openWrite(handle: Uri): OutputStream =
        resolver.openOutputStream(handle, "wt") ?: throw IOException("cannot write to the selected location")

    @Throws(IOException::class)
    override fun openRead(handle: Uri): InputStream =
        resolver.openInputStream(handle) ?: throw IOException("cannot read the selected location back")

    override fun delete(handle: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, handle)
    } catch (_: Exception) {
        false
    }

    override fun hold(handle: Uri): Boolean = try {
        resolver.takePersistableUriPermission(handle, GRANT)
        true
    } catch (_: Exception) {
        false
    }

    override fun release(handle: Uri) {
        try {
            resolver.releasePersistableUriPermission(handle, GRANT)
        } catch (_: Exception) {
        }
    }

    private companion object {
        val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
