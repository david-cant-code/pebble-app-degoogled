package com.anopticlabs.gravel.watchapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString

/** A file past this gets no digest; the Notebook watch app's PBW is far smaller. */
internal const val MAX_DIGEST_PBW_BYTES = 8L * 1024 * 1024

internal fun isRegularFile(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

/** SHA-256 of the file at [path] as lowercase hex, or null when it is missing, not a file, or longer than [maxBytes]. */
internal suspend fun sha256Hex(path: Path, maxBytes: Long = MAX_DIGEST_PBW_BYTES): String? = withContext(Dispatchers.IO) {
    if (!isRegularFile(path)) return@withContext null
    val read = Buffer()
    SystemFileSystem.source(path).buffered().use { source ->
        while (source.readAtMostTo(read, 64 * 1024) != -1L) {
            if (read.size > maxBytes) return@withContext null
        }
    }
    read.readByteArray().toByteString().sha256().hex()
}
