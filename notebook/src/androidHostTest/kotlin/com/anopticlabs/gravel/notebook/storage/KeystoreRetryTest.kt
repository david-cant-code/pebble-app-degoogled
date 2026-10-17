package com.anopticlabs.gravel.notebook.storage

import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class KeystoreRetryTest {

    private val sleeps = mutableListOf<Long>()
    private fun retry(permanent: (Throwable) -> Boolean = { false }) =
        KeystoreRetry(sleep = { sleeps += it }, permanent = permanent)

    @Test
    fun aTransientFailureIsRetriedWithGrowingBackoffUntilItSucceeds() {
        var calls = 0
        val result = retry().run("op") {
            calls++
            if (calls < 3) throw ProviderException("pruned")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(15L, 30L), sleeps)
    }

    @Test
    fun anExhaustedTransientBecomesATransientCryptoExceptionWithTheLastFailureAsCause() {
        var calls = 0
        val last = ProviderException("still pruned")
        val e = assertFailsWith<TransientCryptoException> {
            retry().run<Unit>("open") { calls++; throw if (calls == 5) last else ProviderException("pruned") }
        }
        assertEquals(1 + KeystoreRetry.MAX_RETRIES, calls)
        assertEquals(listOf(15L, 30L, 45L, 60L), sleeps)
        assertSame(last, e.cause)
    }

    @Test
    fun theHookRunsAfterEachBackoffAndOnlyBeforeARetry() {
        val events = mutableListOf<String>()
        val hooked = KeystoreRetry(sleep = { events += "sleep $it" }, permanent = { false }, beforeRetry = {
            assertEquals(KeystoreRetry.MAX_CONCURRENT_OPS, KeystoreRetry.availablePermitsForTest(), "the hook ran holding a permit")
            events += "hook"
        })
        var calls = 0
        assertEquals("ok", hooked.run("op") { calls++; if (calls < 3) throw ProviderException("pruned") else "ok" })
        assertEquals(listOf("sleep 15", "hook", "sleep 30", "hook"), events)

        events.clear()
        assertFailsWith<TransientCryptoException> { hooked.run<Unit>("op") { throw ProviderException("pruned") } }
        assertEquals(KeystoreRetry.MAX_RETRIES, events.count { it == "hook" })
        assertEquals("hook", events.last())

        events.clear()
        runCatching { hooked.run<Unit>("op") { throw AEADBadTagException("tag") } }
        runCatching { hooked.run<Unit>("op") { throw IllegalStateException("x") } }
        runCatching { KeystoreRetry(sleep = {}, permanent = { true }, beforeRetry = { events += "hook" }).run<Unit>("op") { throw ProviderException("key") } }
        assertEquals(emptyList(), events)
    }

    @Test
    fun theFrameworksVerdictDecidesFromApi33AndIsNotAskedBelowIt() {
        val cause = ProviderException("keystore")
        assertFalse(permanentVerdict(32, cause) { fail("asked below API 33") })
        assertFalse(permanentVerdict(33, cause) { assertSame(cause, it); true })
        assertTrue(permanentVerdict(33, cause) { assertSame(cause, it); false })
    }

    @Test
    fun aFailureThePredicateCallsPermanentIsKeyUnavailableWithoutRetry() {
        var calls = 0
        val cause = ProviderException("key state")
        val e = assertFailsWith<KeyUnavailableException> {
            retry(permanent = { true }).run<Unit>("open") { calls++; throw cause }
        }
        assertSame(cause, e.cause)
        assertEquals(1, calls)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun aSecurityExceptionWithoutAMarkerIsRetriedAndThenTransient() {
        var calls = 0
        assertFailsWith<TransientCryptoException> {
            retry().run<Unit>("open") { calls++; throw GeneralSecurityException("key lookup") }
        }
        assertEquals(1 + KeystoreRetry.MAX_RETRIES, calls)
    }

    @Test
    fun aGeneralSecurityExceptionWrappingAProviderExceptionIsTransientNotCorruption() {
        var calls = 0
        assertFailsWith<TransientCryptoException> {
            retry().run<Unit>("open") {
                calls++
                throw IllegalBlockSizeException("doFinal").apply { initCause(ProviderException("HAL")) }
            }
        }
        assertEquals(1 + KeystoreRetry.MAX_RETRIES, calls)
    }

    @Test
    fun aTagFailureIsRethrownAsTheUnderlyingSecurityExceptionWithoutRetry() {
        val tag = AEADBadTagException("tag")
        var calls = 0
        val direct = assertFailsWith<AEADBadTagException> { retry().run<Unit>("open") { calls++; throw tag } }
        assertSame(tag, direct)
        val wrapped = assertFailsWith<GeneralSecurityException> {
            retry().run<Unit>("open") { calls++; throw ProviderException("keystore", tag) }
        }
        assertSame(tag, wrapped)
        assertEquals(2, calls)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun aNonKeystoreFailurePropagatesUnchangedWithoutRetry() {
        var calls = 0
        val boom = IllegalStateException("programming error")
        val e = assertFailsWith<IllegalStateException> { retry().run<Unit>("seal") { calls++; throw boom } }
        assertSame(boom, e)
        val missing = KeyUnavailableException("missing")
        val passed = assertFailsWith<KeyUnavailableException> { retry().run<Unit>("open") { calls++; throw missing } }
        assertSame(missing, passed)
        assertEquals(2, calls)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun atMostThreeOperationsRunAtOnce() {
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val release = CountDownLatch(1)
        val threads = (1..4).map {
            thread {
                retry().run("op") {
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now, ::maxOf)
                    release.await(5, TimeUnit.SECONDS)
                    inside.decrementAndGet()
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (inside.get() < KeystoreRetry.MAX_CONCURRENT_OPS && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(200)
        assertEquals(KeystoreRetry.MAX_CONCURRENT_OPS, inside.get(), "the fourth operation must wait for a permit")
        release.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(3, maxInside.get())
        assertEquals(0, inside.get())
    }

    @Test
    fun everyExitReleasesItsOperationPermit() {
        val before = KeystoreRetry.availablePermitsForTest()
        assertEquals(KeystoreRetry.MAX_CONCURRENT_OPS, before)
        retry().run("op") { 1 }
        runCatching { retry().run<Unit>("op") { throw ProviderException("pruned") } }
        runCatching { retry().run<Unit>("op") { throw AEADBadTagException("tag") } }
        runCatching { retry().run<Unit>("op") { throw IllegalStateException("x") } }
        assertEquals(before, KeystoreRetry.availablePermitsForTest())
    }
}
