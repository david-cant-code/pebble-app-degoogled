package com.anopticlabs.gravel.notebook.storage

/** Authenticated encryption for the Notebook's files at rest; an envelope is bound to its [location] and opens only there. */
interface Cryptographer {
    fun seal(plaintext: ByteArray, location: ByteArray): ByteArray

    fun open(envelope: ByteArray, location: ByteArray): ByteArray
}

/** The envelope header is not the Notebook's format: wrong magic, unknown version or too short. */
class InvalidFormatException(message: String) : RuntimeException(message)

/** The key store or file system failed and may succeed later; the stored bytes stay in place. */
class TransientCryptoException(message: String, cause: Throwable) : RuntimeException(message, cause)

/** The key is missing or the key store reports a permanent failure; the bytes stay in place. */
class KeyUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A file that failed authentication or has a malformed envelope was left in place rather than quarantined. */
class CorruptFileException(message: String, cause: Throwable) : RuntimeException(message, cause)

fun isTransientCryptoFailure(t: Throwable?): Boolean = causeChain(t).any { it is TransientCryptoException }

fun isKeyUnavailableFailure(t: Throwable?): Boolean = causeChain(t).any { it is KeyUnavailableException }

/** One of the failures a store read or write reports, as opposed to a bug. */
fun isStorageFailure(e: Throwable): Boolean =
    e is TransientCryptoException || e is KeyUnavailableException || e is CorruptFileException || e is PersistedFormatException

/** [t] and its causes in order, stopping at a cycle. */
internal fun causeChain(t: Throwable?): Sequence<Throwable> = sequence {
    val seen = HashSet<Throwable>()
    var cur = t
    while (cur != null && seen.add(cur)) {
        yield(cur)
        cur = cur.cause
    }
}
