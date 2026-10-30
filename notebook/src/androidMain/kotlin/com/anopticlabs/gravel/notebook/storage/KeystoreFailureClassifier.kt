package com.anopticlabs.gravel.notebook.storage

import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.BadPaddingException

/** In descending precedence: corruption, a Keystore-layer failure, a programming error. */
enum class KeystoreFailureKind { BAD_TAG, TRANSIENT_KEYSTORE, NON_KEYSTORE }

// Public API only from 33 (android-37.0 api-versions.xml, android/security/KeyStoreException
// since="33"); matched by name so the check compiles at minSdk 26.
internal const val KEYSTORE_EXCEPTION_CLASS = "android.security.KeyStoreException"

internal fun isKeystoreLayerClassName(name: String): Boolean = name == KEYSTORE_EXCEPTION_CLASS

// Only an authentication failure proves the bytes bad; any other GeneralSecurityException is the provider's.
fun classifyKeystoreThrowable(t: Throwable): KeystoreFailureKind {
    var sawKeystoreLayer = false
    for (cur in causeChain(t)) {
        if (cur is BadPaddingException) return KeystoreFailureKind.BAD_TAG
        if (cur is ProviderException || cur is GeneralSecurityException || isKeystoreLayerClassName(cur.javaClass.name)) {
            sawKeystoreLayer = true
        }
    }
    return if (sawKeystoreLayer) KeystoreFailureKind.TRANSIENT_KEYSTORE else KeystoreFailureKind.NON_KEYSTORE
}

fun badPaddingCauseOf(t: Throwable): BadPaddingException? =
    causeChain(t).filterIsInstance<BadPaddingException>().firstOrNull()

internal fun keystoreExceptionCauseOf(t: Throwable): Throwable? =
    causeChain(t).firstOrNull { isKeystoreLayerClassName(it.javaClass.name) }
