package com.anopticlabs.gravel.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AlbumArtSourceTest {
    private val own = setOf("com.anopticlabs.gravel.fileprovider", "com.anopticlabs.gravel.androidx-startup")
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun opensContentUrisFromOtherApps() {
        assertTrue(isOpenableArtUri("content", "media", own))
        assertTrue(isOpenableArtUri("content", "com.example.player.art", own))
        assertTrue(isOpenableArtUri("content", "10@com.example.player.art", own))
    }

    @Test
    fun refusesOtherSchemes() {
        listOf("file", "android.resource", "https", "http", "CONTENT", "Content", null).forEach {
            assertFalse(isOpenableArtUri(it, "media", own), "scheme $it")
        }
    }

    @Test
    fun refusesThisAppsOwnProviders() {
        assertFalse(isOpenableArtUri("content", "com.anopticlabs.gravel.fileprovider", own))
        assertFalse(isOpenableArtUri("content", "0@com.anopticlabs.gravel.fileprovider", own))
        assertFalse(isOpenableArtUri("content", "1@2@com.anopticlabs.gravel.androidx-startup", own))
    }

    @Test
    fun refusesMissingAuthorities() {
        assertFalse(isOpenableArtUri("content", null, own))
        assertFalse(isOpenableArtUri("content", "", own))
        assertFalse(isOpenableArtUri("content", "5@", own))
    }

    @Test
    fun sampleKeepsBothSidesCoveringTheTarget() {
        val targets = listOf(144 to 144, 144 to 168, 200 to 228, 64 to 64)
        val images = listOf(145 to 100, 288 to 288, 4000 to 3000, 3000 to 4000, 1280 to 720, 720 to 1280, 2048 to 1536)
        for ((tw, th) in targets) for ((w, h) in images) {
            val sample = albumArtSampleSize(w, h, tw, th)!!
            if (sample > 1) assertTrue(w / sample >= tw && h / sample >= th, "$w x $h for $tw x $th: sample $sample")
            assertTrue(w / (sample * 2) < tw || h / (sample * 2) < th, "$w x $h for $tw x $th: sample $sample too small")
        }
    }

    @Test
    fun wideArtIsSampledByItsShorterSide() {
        assertEquals(4, albumArtSampleSize(1280, 720, 144, 144))
        assertEquals(4, albumArtSampleSize(720, 1280, 144, 144))
    }

    @Test
    fun extremeAspectRatiosStayWithinTheDecodedCap() {
        listOf(16_000 to 200, 200 to 16_000, 65_536 to 256, 1_000_000 to 16, 16_399 to 100).forEach { (w, h) ->
            val sample = albumArtSampleSize(w, h, 144, 144)!!
            val roundedUp = (maxOf(w, h) + sample - 1) / sample
            assertTrue(roundedUp <= MAX_ALBUM_ART_DECODED_PX, "$w x $h -> sample $sample, up to $roundedUp")
        }
    }

    @Test
    fun smallImagesAreNotSampled() {
        assertEquals(1, albumArtSampleSize(100, 80, 144, 144))
        assertEquals(1, albumArtSampleSize(287, 10, 144, 144))
    }

    @Test
    fun targetIsCappedAndFloored() {
        assertEquals(8, albumArtSampleSize(4096, 4096, 5000, 5000))
        assertEquals(albumArtSampleSize(4096, 4096, 1, 1), albumArtSampleSize(4096, 4096, 0, -3))
    }

    @Test
    fun imagesOverThePixelCapGetNoSample() {
        assertTrue(albumArtSampleSize(4096, 4096, 144, 144) != null)
        assertNull(albumArtSampleSize(4097, 4096, 144, 144))
        assertNull(albumArtSampleSize(30_000, 30_000, 144, 144))
        assertNull(albumArtSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 144, 144))
    }

    @Test
    fun imagesWithNoSizeGetNoSample() {
        assertNull(albumArtSampleSize(0, 100, 144, 144))
        assertNull(albumArtSampleSize(100, -1, 144, 144))
    }

    @Test
    fun readsStreamsUpToTheLimit() {
        val bytes = ByteArray(40_000) { it.toByte() }
        assertContentEquals(bytes, readArtBytes(ByteArrayInputStream(bytes), limit = 40_000))
        assertContentEquals(ByteArray(0), readArtBytes(ByteArrayInputStream(ByteArray(0)), limit = 10))
    }

    @Test
    fun stopsReadingPastTheLimit() {
        assertNull(readArtBytes(ByteArrayInputStream(ByteArray(40_001)), limit = 40_000))
    }

    @Test
    fun readerReturnsTheBytes() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 2_000.milliseconds)
        val bytes = ByteArray(1000) { it.toByte() }
        assertContentEquals(bytes, reader.read({ ByteArrayInputStream(bytes) }, abandon = {}))
    }

    @Test
    fun readerGivesNullWhenTheOpenerThrowsOrHasNoStream() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 2_000.milliseconds)
        assertNull(reader.read({ throw SecurityException("refused") }, abandon = {}))
        assertNull(reader.read({ null }, abandon = {}))
        assertContentEquals(ByteArray(3), reader.read({ ByteArrayInputStream(ByteArray(3)) }, abandon = {}))
    }

    @Test
    fun aStuckProviderTimesOutHoldsTheSlotAndReleasesItWhenItReturns() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 200.milliseconds)
        val release = CountDownLatch(1)
        try {
            val abandons = AtomicInteger()
            val started = System.nanoTime()
            assertNull(withTimeout(5.seconds) { reader.read({ BlockingStream(release) }, abandon = { abandons.incrementAndGet() }) })
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            assertEquals(1, abandons.get())

            val opens = AtomicInteger()
            assertNull(reader.read({ opens.incrementAndGet(); ByteArrayInputStream(ByteArray(1)) }, abandon = {}))
            assertEquals(0, opens.get(), "a second read opened while the first was still stuck")
        } finally {
            release.countDown()
        }
        assertContentEquals(ByteArray(2), readSoon(reader, ByteArray(2)))
    }

    @Test
    fun aReadCancelledBeforeItStartsFreesTheSlot() = runBlocking {
        val single = Executors.newSingleThreadExecutor()
        val dispatcher = single.asCoroutineDispatcher()
        val busy = CountDownLatch(1)
        try {
            single.execute { busy.await() }
            val reader = ArtUriReader(scope, dispatcher, timeout = 200.milliseconds)
            assertNull(reader.read({ ByteArrayInputStream(ByteArray(1)) }, abandon = {}))
            busy.countDown()
            assertContentEquals(ByteArray(4), readSoon(reader, ByteArray(4)))
        } finally {
            busy.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun anOpenThatReturnsAfterTheDeadlineIsClosedUnread() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 200.milliseconds)
        val never = CountDownLatch(1)
        val late = BlockingStream(never)
        try {
            assertNull(reader.read({ Thread.sleep(500); late }, abandon = {}))
            assertContentEquals(ByteArray(5), readSoon(reader, ByteArray(5)))
            assertTrue(late.closed)
        } finally {
            never.countDown()
        }
    }

    @Test
    fun aReadThatIsSlowButNotStuckMakesTheNextOneWait() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 3_000.milliseconds)
        val first = async { reader.read({ Thread.sleep(300); ByteArrayInputStream(ByteArray(6)) }, abandon = {}) }
        delay(50)
        assertContentEquals(ByteArray(7), reader.read({ ByteArrayInputStream(ByteArray(7)) }, abandon = {}))
        assertContentEquals(ByteArray(6), first.await())
    }

    @Test
    fun aCancelledCallerAbandonsTheRead() = runBlocking {
        val reader = ArtUriReader(scope, Dispatchers.IO, timeout = 5_000.milliseconds)
        val release = CountDownLatch(1)
        val abandoned = CountDownLatch(1)
        try {
            val caller = launch { reader.read({ BlockingStream(release) }, abandon = { abandoned.countDown() }) }
            delay(100)
            caller.cancelAndJoin()
            assertTrue(abandoned.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
    }

    // Reads until the reader's slot is free again or five seconds pass.
    private suspend fun readSoon(reader: ArtUriReader, bytes: ByteArray): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var result: ByteArray? = null
        while (result == null && System.nanoTime() < deadline) {
            result = reader.read({ ByteArrayInputStream(bytes) }, abandon = {})
        }
        return result
    }

    private class BlockingStream(private val release: CountDownLatch) : InputStream() {
        @Volatile var closed = false
        override fun read(): Int {
            release.await()
            return -1
        }
        override fun close() {
            closed = true
        }
    }
}
