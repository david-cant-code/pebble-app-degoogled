package com.anopticlabs.gravel.notebook.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientCryptoFailureTest {

    private fun transient() = TransientCryptoException("injected", RuntimeException("inner"))

    @Test
    fun walksTheWholeCauseChain() {
        assertFalse(isTransientCryptoFailure(null))
        assertFalse(isTransientCryptoFailure(RuntimeException("plain")))
        assertTrue(isTransientCryptoFailure(transient()))
        assertTrue(isTransientCryptoFailure(RuntimeException("outer", RuntimeException("mid", transient()))))
    }

    @Test
    fun aCyclicCauseChainTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertFalse(isTransientCryptoFailure(a))
        assertEquals(listOf(a, b), causeChain(a).toList())
    }

    @Test
    fun theThreeStorageFailureClassesDoNotOverlap() {
        val missing = KeyUnavailableException("missing")
        assertFalse(isPersistedFormatFailure(transient()))
        assertFalse(isTransientCryptoFailure(PersistedFormatException("f", RuntimeException())))
        assertTrue(isKeyUnavailableFailure(RuntimeException("outer", missing)))
        assertFalse(isKeyUnavailableFailure(transient()))
        assertFalse(isTransientCryptoFailure(missing))
    }
}
