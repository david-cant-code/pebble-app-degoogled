package com.anopticlabs.gravel.notebook.storage

import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The framework KeyStoreException cannot be instantiated on the JVM (its stub throws), so its
 * match is covered through the name predicate; the throwable cases use JDK exceptions.
 */
class KeystoreFailureClassifierTest {

    @Test
    fun providerExceptionAtTheTopOrInTheChainIsTransientKeystore() {
        assertEquals(KeystoreFailureKind.TRANSIENT_KEYSTORE, classifyKeystoreThrowable(ProviderException("pruned")))
        assertEquals(
            KeystoreFailureKind.TRANSIENT_KEYSTORE,
            classifyKeystoreThrowable(RuntimeException("outer", ProviderException("keystore"))),
        )
        assertEquals(
            KeystoreFailureKind.TRANSIENT_KEYSTORE,
            classifyKeystoreThrowable(GeneralSecurityException("wrapped", ProviderException("keystore"))),
        )
    }

    @Test
    fun tagAndPaddingFailuresAreBadTag() {
        assertEquals(KeystoreFailureKind.BAD_TAG, classifyKeystoreThrowable(AEADBadTagException("tag")))
        assertEquals(KeystoreFailureKind.BAD_TAG, classifyKeystoreThrowable(BadPaddingException("pad")))
    }

    @Test
    fun badTagWinsOverAKeystoreWrapperAboveIt() {
        assertEquals(
            KeystoreFailureKind.BAD_TAG,
            classifyKeystoreThrowable(ProviderException("keystore", AEADBadTagException("tag"))),
        )
    }

    @Test
    fun anyOtherSecurityExceptionIsTheKeystoreLayerNotCorruption() {
        assertEquals(KeystoreFailureKind.TRANSIENT_KEYSTORE, classifyKeystoreThrowable(GeneralSecurityException("no marker")))
        assertEquals(KeystoreFailureKind.TRANSIENT_KEYSTORE, classifyKeystoreThrowable(RuntimeException("outer", GeneralSecurityException("key lookup"))))
    }

    @Test
    fun runtimeFailuresAreNonKeystore() {
        assertEquals(KeystoreFailureKind.NON_KEYSTORE, classifyKeystoreThrowable(IllegalStateException("oops")))
        assertEquals(KeystoreFailureKind.NON_KEYSTORE, classifyKeystoreThrowable(KeyUnavailableException("missing")))
        assertEquals(KeystoreFailureKind.NON_KEYSTORE, classifyKeystoreThrowable(TransientCryptoException("t", RuntimeException())))
    }

    @Test
    fun aCyclicCauseChainTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(KeystoreFailureKind.NON_KEYSTORE, classifyKeystoreThrowable(a))
    }

    @Test
    fun theKeystoreLayerNameMatchesOnlyTheFrameworkClass() {
        assertTrue(isKeystoreLayerClassName("android.security.KeyStoreException"))
        assertFalse(isKeystoreLayerClassName("java.lang.IllegalStateException"))
        assertFalse(isKeystoreLayerClassName("java.security.ProviderException"))
        assertFalse(isKeystoreLayerClassName(""))
        assertNull(keystoreExceptionCauseOf(ProviderException("bare")))
    }

    @Test
    fun badPaddingCauseUnwrapsTheTagFailureOrIsNull() {
        val tag = AEADBadTagException("tag")
        assertSame(tag, badPaddingCauseOf(ProviderException("keystore", tag)))
        val pad = BadPaddingException("pad")
        assertSame(pad, badPaddingCauseOf(pad))
        assertNull(badPaddingCauseOf(ProviderException("no tag")))
        assertNull(badPaddingCauseOf(IllegalStateException("x")))
    }

    @Test
    fun badTagAlwaysHasAPaddingCauseToRethrow() {
        val cases = listOf(AEADBadTagException("a"), BadPaddingException("b"), ProviderException("k", AEADBadTagException("c")))
        for (c in cases) {
            assertEquals(KeystoreFailureKind.BAD_TAG, classifyKeystoreThrowable(c))
            assertNotNull(badPaddingCauseOf(c))
        }
    }
}
