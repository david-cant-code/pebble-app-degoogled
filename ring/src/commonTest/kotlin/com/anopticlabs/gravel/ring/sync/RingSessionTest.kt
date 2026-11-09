@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.anopticlabs.gravel.ring.sync

import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.protocol.bytes
import com.anopticlabs.gravel.ring.protocol.collection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RingSessionTest {

    private val ring = FakeRing()
    private val store = MemoryStore()
    private val sink = RecordingSink()
    private val idle = RingAdvertisement(0x1234_FFFF, 1, needsServicing = false, recording = false)

    private fun collectionReads() = ring.reads.filter { it and 0xFFFF_0000L == 0x4002_0000L }.map { (it and 0xFFFF).toInt() }

    private fun TestScope.session(maxRecordingMillis: Long = 5 * 60 * 1000L) =
        RingSession(ring, store, sink, { 1_900_000_000 }, maxRecordingMillis, testScheduler.timeSource)

    private fun ringHolds(vararg parts: Pair<Int, ByteArray>) {
        parts.forEach { (index, bytes) -> ring.collections[index] = bytes }
        ring.start = parts.minOf { it.first }
        ring.end = parts.maxOf { it.first } + 1
    }

    @Test
    fun aFirstSyncReadsEveryStoredCollectionAndStoresTheRecordings() = runTest {
        ringHolds(5 to ringPart(5, true), 6 to ringPart(6, false), 7 to ringPart(6, true))
        ring.count = 8

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(5, 6, 7), collectionReads())
        assertEquals(listOf(10_000, 20_000), sink.stored.map { it.samples.size })
        assertEquals(listOf(5, 6), sink.firstIndexes)
        assertNull(ring.clockSet)
        assertEquals(8, store.next)
        assertEquals(8, store.count)
    }

    @Test
    fun aSyncResumesAtTheSavedPositionAndReadsNothingWhenItIsAtTheEnd() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true), 2 to ringPart(2, true))
        store.next = 2

        session().run(idle)
        assertEquals(listOf(2), collectionReads())

        ring.reads.clear()
        session().run(idle)
        assertEquals(emptyList(), collectionReads())
        assertEquals(1, sink.stored.size)
    }

    @Test
    fun aSavedPositionTheRingNoLongerHoldsRestartsAtTheRangesStart() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true))
        store.next = 40

        session().run(idle)

        assertEquals(listOf(0, 1), collectionReads())
        assertEquals(2, store.next)
    }

    @Test
    fun anUnfinishedRecordingKeepsThePositionAtItsFirstPartAndIsReadAgainNextTime() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, false))

        session().run(idle)
        assertEquals(1, store.next)
        assertEquals(1, sink.stored.size)

        ring.collections[3] = ringPart(1, true)
        ring.end = 4
        ring.reads.clear()
        session().run(idle)

        assertEquals(listOf(1, 2, 3), collectionReads())
        assertEquals(30_000, sink.stored.last().samples.size)
        assertEquals(4, store.next)
    }

    @Test
    fun aCollectionTheParserRefusesIsSkipped() = runTest {
        ringHolds(0 to collection(bytes(0x99, 0, 0)), 1 to ringPart(1, true))

        session().run(idle)

        assertEquals(1, sink.stored.size)
        assertEquals(2, store.next)
    }

    @Test
    fun aSyncCanceledWhileARecordingIsStoredStillSavesThePositionPastIt() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true))
        lateinit var sync: Job
        // Finishes after the cancel on another dispatcher, as the Notebook's ingest does.
        sink.afterStoring = { withContext(NonCancellable + StandardTestDispatcher(testScheduler)) { sync.cancel() } }

        sync = launch { session().run(idle) }
        sync.join()

        assertTrue(sync.isCancelled)
        assertEquals(1, sink.stored.size)
        assertEquals(1, store.next)
    }

    @Test
    fun aRecordingTheSinkCannotStoreStopsTheSyncBeforeIt() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, true), 3 to ringPart(3, true))
        sink.accept = false
        store.next = 1

        assertEquals(RingSessionEnd.NOT_STORED, session().run(idle))

        assertEquals(1, store.next)
        assertEquals(listOf(1, 2), collectionReads())
        assertNull(store.count)
    }

    @Test
    fun anUnfinishedRecordingPastTheLimitIsDroppedWithItsLaterParts() = runTest {
        ringHolds(0 to ringPart(0, false), 1 to ringPart(0, false))

        session(maxRecordingMillis = 1_500).run(idle)
        assertEquals(2, store.next)

        ringHolds(0 to ringPart(0, false), 1 to ringPart(0, false), 2 to ringPart(0, true), 3 to ringPart(3, true))
        store.next = null
        session(maxRecordingMillis = 1_500).run(idle)

        assertEquals(listOf(3), sink.firstIndexes)
        assertEquals(4, store.next)
    }

    @Test
    fun anUnfinishedGroupIsBoundedInSamplesWhateverRateTheRingClaims() = runTest {
        ringHolds(0 to ringPart(0, false, rate = 100_000), 1 to ringPart(0, false, rate = 100_000), 2 to ringPart(0, false, rate = 100_000))

        session(maxRecordingMillis = 1_500).run(idle)

        assertEquals(3, store.next)
    }

    @Test
    fun aCollectionTheRingRefusesIsPassedWithTheRecordingItBelongedTo() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, true), 3 to ringPart(3, true))
        ring.refuseCollections[2] = 0x05

        assertFailsWith<RingRefusedException> { session().run(idle) }
        assertEquals(3, store.next)

        ring.refuseCollections.clear()
        session().run(idle)
        assertEquals(listOf(0, 3), sink.firstIndexes)
    }

    @Test
    fun aRefusedLastCollectionLeavesTheOpenRecordingToBeReadAgain() = runTest {
        ringHolds(0 to ringPart(1, false), 1 to collection(bytes(0x99, 0, 0)))

        session().run(idle)

        assertEquals(0, store.next)
    }

    @Test
    fun aSyncReadsAcrossTheIndexWrap() = runTest {
        ring.collections[65534] = ringPart(65534, false)
        ring.collections[65535] = ringPart(65534, false)
        ring.collections[0] = ringPart(65534, true)
        ring.start = 65534
        ring.end = 1

        session().run(idle)

        assertEquals(listOf(65534, 65535, 0), collectionReads())
        assertEquals(listOf(65534), sink.firstIndexes)
        assertEquals(1, store.next)
    }

    @Test
    fun aRingAskingForServiceGetsItsClockSetFirst() = runTest {
        ringHolds(0 to ringPart(0, true))

        session().run(idle.copy(needsServicing = true))

        assertEquals(1_900_000_000, ring.clockSet)
        assertEquals(0x4003_0001L, ring.reads.first())
    }

    @Test
    fun whileTheRingIsRecordingTheSessionPollsAndReadsWhatArrives() = runTest {
        ringHolds(0 to ringPart(0, false))
        ring.recording = true
        var polls = 0
        ring.beforeAdvertisementRead = {
            polls++
            if (polls == 2) {
                ring.collections[1] = ringPart(0, true)
                ring.end = 2
                ring.recording = false
            }
        }

        assertEquals(RingSessionEnd.SYNCED, session().run(idle.copy(recording = true)))

        assertEquals(20_000, sink.stored.single().samples.size)
        assertEquals(2, store.next)
    }

    @Test
    fun aRingThatNeverStopsRecordingEndsTheSessionAtItsLimit() = runTest {
        ringHolds(0 to ringPart(0, false))
        ring.recording = true

        assertEquals(RingSessionEnd.STILL_RECORDING, session().run(idle))

        assertTrue(testScheduler.currentTime >= 10.minutes.inWholeMilliseconds)
        assertNull(store.count)
    }

    @Test
    fun aReplyBeforeItsDataIsReadTheSame() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.replyFirst = true

        session().run(idle)

        assertEquals(1, sink.stored.size)
    }

    @Test
    fun aRefusedRequestEndsTheSessionWithThePositionUnchanged() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.refuseStatus = 0x43
        store.next = 0

        val refused = assertFailsWith<RingRefusedException> { session().run(idle) }

        assertEquals(0x43, refused.status)
        assertEquals(0, store.next)
    }

    @Test
    fun aRingThatDoesNotAnswerTimesOut() = runTest {
        ring.silent = true

        assertFailsWith<RingTimeoutException> { session().run(idle) }
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun bytesArrivingBetweenRequestsEndTheSession() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.stray()

        assertFailsWith<RingProtocolException> { session().run(idle) }
        assertEquals(emptyList(), ring.reads)
    }

    @Test
    fun aRingIsSyncedWhenItsCountChangedOrItAsksForServiceOrIsRecording() {
        assertTrue(shouldSyncRing(idle, lastCount = null))
        assertTrue(shouldSyncRing(idle, lastCount = 0))
        assertEquals(false, shouldSyncRing(idle, lastCount = 1))
        assertTrue(shouldSyncRing(idle.copy(needsServicing = true), lastCount = 1))
        assertTrue(shouldSyncRing(idle.copy(recording = true), lastCount = 1))
        assertEquals(false, shouldSyncRing(idle.copy(fingerprint = 0xDEADDEAD), lastCount = 0))
    }

    @Test
    fun aGattErrorIsRetriedAndALostBondOrFailedAuthenticationNeedsPairingAgain() {
        assertEquals(RingLinkFailure.RETRY, ringLinkFailure(133))
        for (status in listOf(137, 5, 15)) assertEquals(RingLinkFailure.PAIR_AGAIN, ringLinkFailure(status))
        for (status in listOf(null, 8, 19, 62)) assertEquals(RingLinkFailure.OTHER, ringLinkFailure(status))
    }
}
