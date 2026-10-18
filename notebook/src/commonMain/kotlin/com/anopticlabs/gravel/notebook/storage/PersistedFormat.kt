package com.anopticlabs.gravel.notebook.storage

import co.touchlab.kermit.Logger

/** Authentic plaintext that failed to parse; the message reaches the user through backup and restore. */
class PersistedFormatException(label: String, cause: Throwable) : RuntimeException(
    "Data file '$label' is unreadable: it decrypted but failed to parse",
    cause,
)

/**
 * The parse step of every encrypted JSON store: a throwing [parse] is a [PersistedFormatException]
 * when [strict] and [empty] otherwise; null is [empty]; a storage failure keeps its class.
 */
fun <T> parsePersisted(strict: Boolean, label: String, empty: () -> T, parse: () -> T?): T {
    val parsed = try {
        parse()
    } catch (e: TransientCryptoException) {
        throw e
    } catch (e: KeyUnavailableException) {
        throw e
    } catch (e: PersistedFormatException) {
        throw e
    } catch (e: Exception) {
        if (strict) throw PersistedFormatException(label, e)
        logger.w(e) { "non-strict parse of '$label' failed; treating as empty" }
        return empty()
    }
    return parsed ?: empty()
}

fun isPersistedFormatFailure(t: Throwable?): Boolean = causeChain(t).any { it is PersistedFormatException }

private val logger = Logger.withTag("NotebookStorage")
