package com.anopticlabs.gravel.notebook.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

enum class FolderAccess { OK, NO_ACCESS, GONE, UNAVAILABLE }

/** A document in a backup folder; [sizeBytes] null when its app reports none. */
data class FolderChild<H>(val handle: H, val name: String, val sizeBytes: Long?)

/** A document could not be made in the folder, for [problem]. */
class FolderException(val problem: AutoBackupProblem, cause: Throwable? = null) : IOException("backup folder: $problem", cause)

/** What automatic backups need from the folder the user granted, named by a handle string, over document handles [H]. */
interface BackupFolderOps<H> : BackupDocumentOps<H> {
    fun access(folder: String): FolderAccess

    /** A new empty document named [name] in [folder]; throws [FolderException]. */
    fun create(folder: String, name: String): H

    /** Renames [handle] to [name] and returns the handle it has afterwards; throws [IOException]. */
    fun rename(handle: H, name: String): H

    /** Empty when the folder cannot be listed. */
    fun children(folder: String): List<FolderChild<H>>

    fun folderName(folder: String): String?

    /** Keeps access to [folder] past this process; false when there was no grant to keep. */
    fun holdFolder(folder: String): Boolean

    fun releaseFolder(folder: String)

    /** Every folder Gravel holds access to past this process. */
    fun heldFolders(): List<String>

    fun encode(handle: H): String

    fun decode(text: String): H
}

/** [BackupFolderOps] over a Storage Access Framework tree the user picked. */
class BackupFolderDocuments(private val resolver: ContentResolver) :
    BackupFolderOps<Uri>, BackupDocumentOps<Uri> by BackupDocuments(resolver) {

    /**
     * Asks the folder's app directly, so an app that cannot be reached tells apart from a folder it
     * no longer has: a reached `DocumentsProvider` answers a missing document with no cursor
     * (android16-release, `DocumentsProvider.query`, which catches `FileNotFoundException`).
     */
    override fun access(folder: String): FolderAccess {
        val tree = Uri.parse(folder)
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return FolderAccess.NO_ACCESS
        val document = folderDocument(tree)
        val client = try {
            resolver.acquireUnstableContentProviderClient(document)
        } catch (_: SecurityException) {
            return FolderAccess.NO_ACCESS
        } ?: return FolderAccess.UNAVAILABLE
        return try {
            client.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { if (it.moveToFirst()) FolderAccess.OK else FolderAccess.GONE }
                ?: FolderAccess.GONE
        } catch (_: SecurityException) {
            FolderAccess.NO_ACCESS
        } catch (_: Exception) {
            FolderAccess.UNAVAILABLE
        } finally {
            client.close()
        }
    }

    override fun create(folder: String, name: String): Uri {
        // `DocumentsProvider.createDocument` declares only FileNotFoundException (android16-release), which
        // does not say whether the folder is gone, so only a refused grant is final here.
        val created = try {
            DocumentsContract.createDocument(resolver, folderDocument(Uri.parse(folder)), BACKUP_MIME_TYPE, name)
        } catch (e: SecurityException) {
            throw FolderException(AutoBackupProblem.NO_ACCESS, e)
        } catch (e: Exception) {
            throw FolderException(AutoBackupProblem.NOT_CREATED, e)
        }
        return created ?: throw FolderException(AutoBackupProblem.NOT_CREATED)
    }

    override fun rename(handle: Uri, name: String): Uri = try {
        DocumentsContract.renameDocument(resolver, handle, name) ?: throw IOException("rename refused")
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("rename failed", e)
    }

    override fun children(folder: String): List<FolderChild<Uri>> {
        val tree = Uri.parse(folder)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            resolver.query(uri, CHILD_COLUMNS, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        add(FolderChild(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, if (c.isNull(2)) null else c.getLong(2)))
                    }
                }
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun folderName(folder: String): String? = try {
        resolver.query(folderDocument(Uri.parse(folder)), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    override fun holdFolder(folder: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(folder), GRANT)
        true
    } catch (_: Exception) {
        false
    }

    override fun releaseFolder(folder: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(folder), GRANT)
        } catch (_: Exception) {
        }
    }

    // android16-release DocumentsContract: buildTreeDocumentUri makes tree/<id>, buildDocumentUriUsingTree appends
    // document/<id>, and isTreeUri matches both, so the segments are counted here.
    override fun heldFolders(): List<String> = try {
        resolver.persistedUriPermissions.map { it.uri }.filter { it.pathSegments.size == 2 && it.pathSegments[0] == "tree" }.map { it.toString() }
    } catch (_: Exception) {
        emptyList()
    }

    override fun encode(handle: Uri): String = handle.toString()

    override fun decode(text: String): Uri = Uri.parse(text)

    private fun folderDocument(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private companion object {
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
