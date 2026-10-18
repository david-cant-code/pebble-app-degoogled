package com.anopticlabs.gravel.notebook.storage

/**
 * Delegates to a real [Cryptographer] and injects failures by location, so a store test can make one
 * specific read or write fail transiently or permanently without a Keystore.
 */
class FakeCryptographer(private val real: Cryptographer) : Cryptographer {

    @Volatile var failOpenWithTransient: Boolean = false
    @Volatile var failOpenWithKeyUnavailable: Boolean = false
    @Volatile var failOpenWhenAadContains: String? = null
    @Volatile var keyUnavailableWhenAadContains: String? = null
    @Volatile var failSealWhenAadContains: String? = null
    @Volatile var failSealWithErrorWhenAadContains: String? = null
    @Volatile var failSealWithOutOfMemoryWhenAadContains: String? = null

    /** Called with the location before every open, on the opening thread; a test can block there. */
    @Volatile var beforeOpen: ((String) -> Unit)? = null

    /** Called with the location before every seal, on the sealing thread; a test can block there. */
    @Volatile var beforeSeal: ((String) -> Unit)? = null

    /** Every location sealed so far, in order. */
    val sealed = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun seal(plaintext: ByteArray, location: ByteArray): ByteArray {
        val text = location.decodeToString()
        beforeSeal?.invoke(text)
        sealed += text
        failSealWhenAadContains?.let { if (text.contains(it)) throw injectedTransient("seal") }
        failSealWithErrorWhenAadContains?.let { if (text.contains(it)) throw RuntimeException("injected non-transient seal failure") }
        failSealWithOutOfMemoryWhenAadContains?.let { if (text.contains(it)) throw OutOfMemoryError("injected") }
        return real.seal(plaintext, location)
    }

    override fun open(envelope: ByteArray, location: ByteArray): ByteArray {
        beforeOpen?.invoke(location.decodeToString())
        if (failOpenWithKeyUnavailable) throw KeyUnavailableException("injected missing key")
        keyUnavailableWhenAadContains?.let { if (location.decodeToString().contains(it)) throw KeyUnavailableException("injected missing key") }
        val trigger = failOpenWhenAadContains
        if (failOpenWithTransient || (trigger != null && location.decodeToString().contains(trigger))) {
            throw injectedTransient("open")
        }
        return real.open(envelope, location)
    }

    private fun injectedTransient(op: String) =
        TransientCryptoException("injected transient $op failure", RuntimeException("injected"))
}
