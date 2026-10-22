package com.anopticlabs.gravel.notebook.storage

import java.security.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The unavailable case uses a marker predicate: the framework's exception cannot be built on the JVM. */
class StrongBoxRetryTest {

    private val strongBoxKey = fixedKey(1)
    private val teeKey = fixedKey(2)
    private val sleeps = mutableListOf<Long>()

    private fun generate(strongBox: () -> javax.crypto.SecretKey, unavailable: (ProviderException) -> Boolean = { false }) =
        NotebookKeystore.generateWithStrongBoxRetry(strongBox, { teeKey }, { sleeps += it }, unavailable)

    @Test
    fun aBusySecureElementIsRetriedAndTheKeyStaysInStrongBox() {
        var calls = 0
        val key = generate({ calls++; if (calls < 3) throw ProviderException("busy") else strongBoxKey })
        assertSame(strongBoxKey, key)
        assertEquals(3, calls)
        assertEquals(listOf(15L, 30L), sleeps)
    }

    @Test
    fun aSecureElementThatKeepsFailingGivesWayToTheTee() {
        var calls = 0
        val key = generate({ calls++; throw ProviderException("busy") })
        assertSame(teeKey, key)
        assertEquals(1 + NotebookKeystore.STRONGBOX_RETRIES, calls)
        assertEquals(listOf(15L, 30L, 45L, 60L), sleeps)
    }

    @Test
    fun anUnavailableSecureElementGivesWayAtOnce() {
        var calls = 0
        val key = generate({ calls++; throw ProviderException("no strongbox") }, unavailable = { true })
        assertSame(teeKey, key)
        assertEquals(1, calls)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun anyOtherFailurePropagates() {
        val boom = IllegalStateException("spec")
        val e = assertFailsWith<IllegalStateException> { generate({ throw boom }) }
        assertSame(boom, e)
        assertEquals(emptyList(), sleeps)
    }
}
