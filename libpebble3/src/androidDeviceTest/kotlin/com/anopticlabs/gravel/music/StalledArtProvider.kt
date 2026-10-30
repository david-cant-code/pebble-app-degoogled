package com.anopticlabs.gravel.music

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

/** Serves `/ok` as eight zero bytes and `/stalled` as a pipe nothing ever writes to. */
class StalledArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        if (uri.path == "/ok") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(ByteArray(8)) }
        } else {
            heldWriteEnds += write
        }
        return read
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.anopticlabs.gravel.music.test.art"
        val heldWriteEnds = CopyOnWriteArrayList<ParcelFileDescriptor>()
    }
}
