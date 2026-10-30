package com.anopticlabs.gravel.notebook.backup

import kotlinx.serialization.Serializable

/** The Notebook archive format; `ArchiveWriter` describes the layout. */
object ArchiveFormat {
    val MAGIC: ByteArray get() = "GNBK".encodeToByteArray()
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1

    /** HKDF-SHA256 (RFC 5869) over a [RecoveryKey] and the archive's salt, with [RECOVERY_KEY_INFO]. */
    const val KDF_RECOVERY_KEY = 2
    const val RECOVERY_KEY_INFO = "GNBK recovery key"
    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_BITS = 256
    /** Counted in Unicode code points of the NFC-normalized password. */
    const val MIN_PASSWORD_LENGTH = 15
    const val SALT_LEN = 16
    const val NONCE_PREFIX_LEN = 7
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val HEADER_LEN = 4 + 2 + 1 + SALT_LEN + NONCE_PREFIX_LEN
    const val SEGMENT_BYTES = 64 * 1024
    const val RECORD_HEADER_LEN = 5

    /** Above the capture queue's 32 MiB blob cap; a longer record is refused before its body is read. */
    const val MAX_RECORD_BYTES = 40 * 1024 * 1024

    /** END holds a count per type and a hash, so it gets a far smaller cap of its own. */
    const val MAX_END_BYTES = 4096
}

/** A record's kind, by its byte on the wire. */
enum class RecordType(val wire: Int) {
    META(1),
    FOLDERS(2),
    FOLDER_TAGS(3),
    NOTE(4),
    CAPTURE(5),
    REMINDERS(6),
    EVENTS(7),
    END(8),
    ;

    companion object {
        fun of(wire: Int): RecordType? = entries.firstOrNull { it.wire == wire }
    }
}

/** The closing record: how many records of each wire type came before it, and the SHA-256 (hex) of their framed bytes. */
@Serializable
data class ArchiveEnd(val records: Map<Int, Int>, val sha256: String)
