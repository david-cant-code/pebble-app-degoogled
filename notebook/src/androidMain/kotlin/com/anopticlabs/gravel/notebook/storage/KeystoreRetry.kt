package com.anopticlabs.gravel.notebook.storage

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import co.touchlab.kermit.Logger
import java.util.concurrent.Semaphore

/**
 * Runs one Keystore cipher operation under the process-wide cap and retries transient failures
 * with a growing backoff. The cap keeps this process from over-subscribing StrongBox, whose
 * in-flight operations keystore2 prunes past its few slots (AOSP main,
 * system/security/keystore2/src/operation.rs, OperationDb::prune). [beforeRetry] runs after each
 * backoff, outside the cap.
 */
internal class KeystoreRetry(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val permanent: (Throwable) -> Boolean = ::isPermanentKeystoreFailure,
    private val beforeRetry: () -> Unit = {},
) {
    fun <T> run(op: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return withPermit(block)
            } catch (e: Exception) {
                when (classifyKeystoreThrowable(e)) {
                    KeystoreFailureKind.BAD_TAG -> throw badPaddingCauseOf(e) ?: e
                    KeystoreFailureKind.NON_KEYSTORE -> throw e
                    KeystoreFailureKind.TRANSIENT_KEYSTORE -> {
                        if (permanent(e)) throw KeyUnavailableException("$op: the key store reports a permanent failure", e)
                        if (attempt < MAX_RETRIES) {
                            attempt++
                            logger.w(e) { "$op: transient Keystore failure, retry $attempt of $MAX_RETRIES" }
                            backoff(BACKOFF_MS * attempt)
                            beforeRetry()
                            continue
                        }
                        throw TransientCryptoException("$op failed at the Keystore layer after $attempt retries", e)
                    }
                }
            }
        }
    }

    // The permit covers only the cipher operation, never a backoff sleep.
    private inline fun <T> withPermit(block: () -> T): T {
        try {
            permits.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransientCryptoException("interrupted waiting for a Keystore operation slot", e)
        }
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    private fun backoff(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransientCryptoException("interrupted during a Keystore retry backoff", e)
        }
    }

    companion object {
        const val MAX_RETRIES = 4
        const val BACKOFF_MS = 15L
        const val MAX_CONCURRENT_OPS = 3

        // Process-wide: one secure element per process; fair so a foreground read is not starved.
        private val permits = Semaphore(MAX_CONCURRENT_OPS, true)
        private val logger = Logger.withTag("NotebookCrypto")

        internal fun availablePermitsForTest(): Int = permits.availablePermits()
    }
}

// android16-release KeyStore2.handleRemoteExceptionWithRetry throws SYSTEM_ERROR when a call fails again on a
// re-fetched binder, which KeyStoreException's error table does not mark transient.
internal fun isPermanentKeystoreFailure(t: Throwable): Boolean {
    val ks = keystoreExceptionCauseOf(t) ?: return false
    return permanentOnThisDevice(ks) { (it as android.security.KeyStoreException).isTransientFailure }
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU, lambda = 1)
private fun permanentOnThisDevice(keystoreCause: Throwable, isTransient: (Throwable) -> Boolean): Boolean =
    permanentVerdict(Build.VERSION.SDK_INT, keystoreCause, isTransient)

// From API 33 the framework gives the verdict (android-37.0 api-versions.xml,
// KeyStoreException.isTransientFailure); without it nothing is treated as permanent.
internal fun permanentVerdict(sdk: Int, keystoreCause: Throwable, isTransient: (Throwable) -> Boolean): Boolean =
    sdk >= Build.VERSION_CODES.TIRAMISU && !isTransient(keystoreCause)
