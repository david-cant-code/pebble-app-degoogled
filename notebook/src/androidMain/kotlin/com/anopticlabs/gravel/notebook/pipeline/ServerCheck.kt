package com.anopticlabs.gravel.notebook.pipeline

/**
 * The "check your server" hold: after a server error, captures stay off the server until Try again or a change of
 * the server settings, read as a stamp. Kept in memory, so after a restart the server is tried once more. Try again
 * lets the capture that started the hold through for its next request ([take]): a server error there, with the same
 * settings, makes it the one to flag. A hold whose capture went on to the phone ([fellBack]) lets nothing through.
 */
class ServerCheck {
    private class Failure(val captureId: String?, val stamp: Int)

    private val lock = Any()
    private var hold: Failure? = null
    private val retries = mutableMapOf<String, Int>()

    /** Whether captures stay off the server; a settings change since the hold ends it. */
    fun held(stamp: Int): Boolean = synchronized(lock) {
        val current = hold ?: return false
        if (current.stamp != stamp) hold = null
        hold != null
    }

    /** Called just before [captureId]'s request: the stamp Try again let it through under, if it did; used up either way. */
    fun take(captureId: String): Int? = synchronized(lock) { retries.remove(captureId) }

    /** A server error for [captureId], [taken] from [take]: true when it was let through under [stamp], which the caller flags; otherwise the hold starts. */
    fun failed(captureId: String, stamp: Int, taken: Int?): Boolean = synchronized(lock) {
        val again = taken == stamp
        if (!again) hold = Failure(captureId, stamp)
        again
    }

    /** A server error for a capture that goes on to the phone: the hold starts, with nothing to flag. */
    fun fellBack(stamp: Int) = synchronized(lock) {
        hold = Failure(null, stamp)
    }

    fun tryAgain() = synchronized(lock) {
        hold?.let { failure -> failure.captureId?.let { retries[it] = failure.stamp } }
        hold = null
    }
}
