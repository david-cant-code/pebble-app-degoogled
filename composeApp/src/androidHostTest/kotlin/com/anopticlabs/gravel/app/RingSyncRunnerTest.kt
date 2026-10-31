package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.link.RingLinkException
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RingSyncRunnerTest {
    private val address = "A1:B2:C3:D4:E5:F6"
    private val paired = MutableStateFlow<String?>(address)
    private val heard = Channel<RingAdvertisement>(Channel.UNLIMITED)
    private var searches = 0
    private var failSearch: Exception? = null
    private var failAfterHearing = false
    private val synced = mutableListOf<Int>()
    private var outcome: suspend () -> RingSessionEnd = { RingSessionEnd.SYNCED }
    private var count: Int? = null
    private var countReads = 0
    private var readable = true
    private var waitReason: suspend () -> RingWait? = { null }
    private var lastWait: RingWait? = null
    private var imported = 0
    private var startOver: suspend (String) -> Unit = {}
    private var syncHold = 30.seconds

    private fun TestScope.runner() = RingSyncRunner(
        paired = paired,
        hear = {
            searches++
            when {
                failAfterHearing -> flow {
                    emit(ad(1))
                    throw IllegalStateException("scan stopped")
                }
                else -> failSearch?.let { e -> flow<RingAdvertisement> { throw e } } ?: heard.receiveAsFlow()
            }
        },
        sync = { _, advertisement ->
            synced += advertisement.collectionCount
            outcome().also { if (it == RingSessionEnd.SYNCED) count = advertisement.collectionCount }
        },
        lastCount = {
            countReads++
            count
        },
        positionReadable = { readable },
        waitReason = { waitReason() },
        lastWait = { lastWait },
        imported = { imported },
        startOver = { startOver(it) },
        clock = { testScheduler.currentTime },
        marks = testScheduler.timeSource,
        syncHold = syncHold,
    ).also { it.start(backgroundScope) }

    private fun ad(count: Int, fingerprint: Long = 0x1234_FFFF, recording: Boolean = false) =
        RingAdvertisement(fingerprint, count, needsServicing = false, recording = recording)

    private fun TestScope.hear(count: Int, fingerprint: Long = 0x1234_FFFF) {
        heard.trySend(ad(count, fingerprint))
        runCurrent()
    }

    @Test
    fun theFirstAdvertisementSyncsAndOneWithTheStoredCountDoesNot() = runTest {
        val runner = runner()
        hear(4)
        advanceTimeBy(1.minutes)
        hear(4)
        hear(5)

        assertEquals(listOf(4, 5), synced)
        assertEquals(RingSyncState.Synced(testScheduler.currentTime, 0), runner.state.value)
    }

    @Test
    fun aSyncIsFollowedByThirtySecondsWithoutOne() = runTest {
        runner()
        hear(4)
        advanceTimeBy(29.seconds)
        hear(5)
        assertEquals(listOf(4), synced)
        advanceTimeBy(1.seconds)
        hear(5)
        assertEquals(listOf(4, 5), synced)
    }

    @Test
    fun anAdvertisementHeardDuringASessionDoesNotStartAnother() = runTest {
        val release = CompletableDeferred<Unit>()
        outcome = {
            release.await()
            RingSessionEnd.SYNCED
        }
        runner()
        hear(1)
        heard.trySend(ad(1, recording = true))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        outcome = { RingSessionEnd.SYNCED }
        advanceTimeBy(30.seconds)
        runCurrent()

        assertEquals(listOf(1), synced)
    }

    @Test
    fun anAdvertisementHeardDuringASessionIsDroppedEvenWithoutAHold() = runTest {
        syncHold = 0.seconds
        val release = CompletableDeferred<Unit>()
        outcome = {
            release.await()
            RingSessionEnd.SYNCED
        }
        runner()
        hear(1)
        heard.trySend(ad(1, recording = true))
        runCurrent()
        outcome = { RingSessionEnd.SYNCED }
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(1), synced)
    }

    @Test
    fun theStoredCountIsReadOnceAtStartAndAfterEachSession() = runTest {
        runner()
        hear(1)
        advanceTimeBy(1.minutes)
        repeat(5) { hear(1) }

        assertEquals(2, countReads)
    }

    @Test
    fun aRingInItsFailsafeOrFactoryTestImageIsNeverConnectedTo() = runTest {
        runner()
        hear(1, 0xDEADDEAD)
        hear(2, 0xDEADBEEF)
        advanceTimeBy(7.hours)
        hear(3, 0xDEADDEAD)

        assertTrue(synced.isEmpty())
    }

    @Test
    fun aSyncedStateCountsTheRecordingsTheSinkImported() = runTest {
        outcome = { imported += 2; RingSessionEnd.SYNCED }
        val runner = runner()
        hear(1)

        assertEquals(2, (runner.state.value as RingSyncState.Synced).added)
    }

    @Test
    fun aSyncIsDueAgainSixHoursAfterTheLastEvenWithTheSameCount() = runTest {
        runner()
        hear(4)
        advanceTimeBy(6.hours - 1.seconds)
        hear(4)
        advanceTimeBy(1.seconds)
        hear(4)

        assertEquals(listOf(4, 4), synced)
    }

    @Test
    fun aRetryableLinkFailureIsTriedFiveTimesInAllThenBacksOff() = runTest {
        outcome = { throw RingLinkException(133, null) }
        val runner = runner()
        hear(1)
        advanceTimeBy(10.seconds)

        assertEquals(MAX_RING_TRIES, synced.size)
        assertTrue(runner.state.value is RingSyncState.Failed)
        hear(1)
        assertEquals(MAX_RING_TRIES, synced.size, "held off for 30 s")
        advanceTimeBy(30.seconds)
        hear(1)
        advanceTimeBy(10.seconds)
        assertEquals(MAX_RING_TRIES * 2, synced.size)
    }

    @Test
    fun theHoldAfterFailuresDoublesUpToFifteenMinutesAndASyncResetsIt() = runTest {
        outcome = { throw IllegalStateException("broken") }
        runner()
        val holds = listOf(30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes, 15.minutes, 15.minutes)
        for (hold in holds) {
            hear(1)
            val tried = synced.size
            advanceTimeBy(hold - 1.seconds)
            hear(1)
            assertEquals(tried, synced.size, "still held at $hold")
            advanceTimeBy(1.seconds)
        }
        outcome = { RingSessionEnd.SYNCED }
        hear(1)
        advanceTimeBy(30.seconds)
        outcome = { throw IllegalStateException("broken") }
        hear(2)
        advanceTimeBy(30.seconds)
        val tried = synced.size
        hear(2)
        assertEquals(tried + 1, synced.size, "back to 30 s after a sync")
    }

    @Test
    fun aLostBondAsksToPairAgainWithoutRetrying() = runTest {
        outcome = { throw RingLinkException(137, null) }
        val runner = runner()
        hear(1)

        assertEquals(1, synced.size)
        assertEquals(RingSyncState.PairAgain, runner.state.value)
    }

    @Test
    fun whileTheSinkWouldWaitNoConnectionIsMade() = runTest {
        waitReason = { RingWait.NOTEBOOK_OFF }
        val runner = runner()
        hear(1)

        assertTrue(synced.isEmpty())
        assertEquals(RingSyncState.Waiting(RingWait.NOTEBOOK_OFF), runner.state.value)
        waitReason = { null }
        hear(1)
        assertTrue(synced.isEmpty(), "checked again after a minute")
        advanceTimeBy(1.minutes)
        hear(1)
        assertEquals(listOf(1), synced)
    }

    @Test
    fun aWaitIsShownOnlyWhenTheRingHasSomethingToSync() = runTest {
        val runner = runner()
        hear(1)
        waitReason = { RingWait.NOTEBOOK_OFF }
        advanceTimeBy(6.hours)
        hear(1)

        assertEquals(listOf(1), synced)
        assertTrue(runner.state.value is RingSyncState.Synced, "the 6-hourly sync found nothing new to wait with")
    }

    @Test
    fun aNotebookCheckThatFailsOrHangsIsAWaitNotACrash() = runTest {
        waitReason = { throw IOException("queue unreadable") }
        val runner = runner()
        hear(1)
        assertTrue(synced.isEmpty())
        advanceTimeBy(1.minutes)
        waitReason = { awaitCancellation() }
        hear(1)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(synced.isEmpty())
        advanceTimeBy(1.minutes)
        waitReason = { null }
        hear(1)

        assertEquals(listOf(1), synced)
        assertTrue(runner.state.value is RingSyncState.Synced)
    }

    @Test
    fun aSessionTheSinkStoppedShowsWhyAndBacksOff() = runTest {
        outcome = { RingSessionEnd.NOT_STORED }
        lastWait = RingWait.TOO_MANY_WAITING
        val runner = runner()
        hear(1)
        hear(1)

        assertEquals(1, synced.size)
        assertEquals(RingSyncState.Waiting(RingWait.TOO_MANY_WAITING), runner.state.value)
    }

    @Test
    fun aDamagedPositionStopsSyncingUntilTheUserImportsAgain() = runTest {
        outcome = { throw RingPositionUnreadableException() }
        val runner = runner()
        hear(1)
        advanceTimeBy(1.hours)
        hear(2)

        assertEquals(1, synced.size)
        assertEquals(RingSyncState.PositionLost, runner.state.value)
        assertTrue(runner.positionLost.value)
        outcome = { RingSessionEnd.SYNCED }
        val cleared = mutableListOf<String>()
        startOver = { cleared += it }
        runner.importAgain()
        hear(2)
        assertEquals(listOf(address), cleared)
        assertEquals(listOf(1, 2), synced)
        assertFalse(runner.positionLost.value)
    }

    @Test
    fun aDamagedPositionFoundAtStartStopsSyncingBeforeAnyConnection() = runTest {
        readable = false
        val runner = runner()
        hear(1)

        assertTrue(synced.isEmpty())
        assertEquals(RingSyncState.PositionLost, runner.state.value)
    }

    @Test
    fun importAgainThatCannotClearThePositionKeepsSyncingStopped() = runTest {
        readable = false
        startOver = { throw IOException("not cleared") }
        val runner = runner()
        runCurrent()
        runner.importAgain()
        hear(1)

        assertTrue(runner.positionLost.value)
        assertTrue(synced.isEmpty())
    }

    @Test
    fun aSessionPastItsLimitFailsUnlessItImportedSomething() = runTest {
        outcome = { awaitCancellation() }
        val runner = runner()
        hear(1)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertTrue(runner.state.value is RingSyncState.Failed)

        advanceTimeBy(30.seconds)
        outcome = {
            imported++
            awaitCancellation()
        }
        hear(1)
        advanceTimeBy(15.minutes)
        runCurrent()
        assertEquals(RingSyncState.Synced(testScheduler.currentTime, 1), runner.state.value)
        outcome = { RingSessionEnd.SYNCED }
        hear(1)
        assertEquals(3, synced.size, "the next session starts at once")
    }

    @Test
    fun aCancellationFromInsideASessionIsAFailureAndTheRunnerGoesOn() = runTest {
        outcome = { throw CancellationException("from the link") }
        val runner = runner()
        hear(1)
        assertTrue(runner.state.value is RingSyncState.Failed)

        outcome = { RingSessionEnd.SYNCED }
        advanceTimeBy(30.seconds)
        hear(1)
        assertEquals(2, synced.size)
    }

    @Test
    fun aFailedSearchIsFlaggedAndStartedAgainAfterThreeSeconds() = runTest {
        failSearch = IllegalStateException("no permission")
        val runner = runner()
        runCurrent()

        assertTrue(runner.searchFailed.value)
        failSearch = null
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(2, searches)
        hear(1)
        assertFalse(runner.searchFailed.value)
        assertTrue(runner.state.value is RingSyncState.Synced)
    }

    @Test
    fun aFailedSearchLeavesTheSyncStateAlone() = runTest {
        readable = false
        failSearch = IllegalStateException("scanning too frequently")
        val runner = runner()
        runCurrent()

        assertTrue(runner.searchFailed.value)
        assertEquals(RingSyncState.PositionLost, runner.state.value)
        assertTrue(runner.positionLost.value)
    }

    @Test
    fun searchesThatKeepFailingAreStartedAgainLessOftenUpToFiveMinutes() = runTest {
        failSearch = IllegalStateException("scanning too frequently")
        runner()
        runCurrent()
        val gaps = listOf(3.seconds, 6.seconds, 12.seconds, 24.seconds, 48.seconds, 96.seconds, 192.seconds, 5.minutes, 5.minutes)
        for ((i, gap) in gaps.withIndex()) {
            advanceTimeBy(gap - 1.seconds)
            runCurrent()
            assertEquals(i + 1, searches, "not yet at $gap")
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(i + 2, searches, "again after $gap")
        }
    }

    @Test
    fun aSearchThatFailsAfterHearingTheRingStartsAgainAfterThreeSeconds() = runTest {
        failAfterHearing = true
        val runner = runner()
        runCurrent()
        repeat(3) {
            advanceTimeBy(3.seconds)
            runCurrent()
        }

        assertEquals(4, searches)
        assertFalse(runner.searchFailed.value)
    }

    @Test
    fun aSearchThatFailsDuringASyncLetsTheSyncFinish() = runTest {
        val release = CompletableDeferred<Unit>()
        outcome = {
            release.await()
            RingSessionEnd.SYNCED
        }
        failAfterHearing = true
        val runner = runner()
        runCurrent()
        assertEquals(RingSyncState.Syncing, runner.state.value)
        release.complete(Unit)
        runCurrent()

        assertTrue(runner.state.value is RingSyncState.Synced)
    }

    @Test
    fun forgettingTheRingStopsItsSyncs() = runTest {
        val runner = runner()
        hear(1)
        paired.value = null
        runCurrent()
        heard.trySend(ad(2))
        runCurrent()

        assertEquals(listOf(1), synced)
        assertEquals(RingSyncState.Listening, runner.state.value)
    }

    @Test
    fun theLastHeardTimeFollowsEachAdvertisement() = runTest {
        val runner = runner()
        advanceTimeBy(5.seconds)
        hear(1)

        assertEquals(testScheduler.currentTime, runner.lastHeard.value)
    }

    @Test
    fun aStoppedRunnerSaysSoAndASecondStartWhileRunningSearchesOnce() = runTest {
        val runner = runner()
        runner.start(backgroundScope)
        runCurrent()
        assertEquals(1, searches)

        runner.stop()
        hear(1)
        assertTrue(synced.isEmpty())
        assertEquals(RingSyncState.Stopped, runner.state.value)
    }

    @Test
    fun aRunStoppedDuringASessionEndsStoppedAndImportAgainLeavesItSo() = runTest {
        val release = CompletableDeferred<Unit>()
        outcome = {
            withContext(NonCancellable) { release.await() }
            throw RingPositionUnreadableException()
        }
        val runner = runner()
        hear(1)
        runner.stop()
        release.complete(Unit)
        runCurrent()
        assertEquals(RingSyncState.Stopped, runner.state.value)

        runner.importAgain()
        assertEquals(RingSyncState.Stopped, runner.state.value)
    }

    @Test
    fun aSearchThatKeepsRunningClearsTheFailedSearchFlag() = runTest {
        failSearch = IllegalStateException("scanning too frequently")
        val runner = runner()
        runCurrent()
        assertTrue(runner.searchFailed.value)
        failSearch = null
        advanceTimeBy(3.seconds)
        runCurrent()
        advanceTimeBy(9.seconds)
        assertTrue(runner.searchFailed.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(runner.searchFailed.value, "running for 10 s without hearing the ring")
    }

    @Test
    fun aRestartWaitsForTheStoppedRunToFinishItsSession() = runTest {
        val release = CompletableDeferred<Unit>()
        var finishing = false
        outcome = {
            // As the session's own store step does.
            withContext(NonCancellable) {
                finishing = true
                release.await()
                finishing = false
            }
            RingSessionEnd.SYNCED
        }
        val runner = runner()
        hear(1)
        runner.stop()
        runner.start(backgroundScope)
        runCurrent()
        assertEquals(1, searches, "the new run waits")

        release.complete(Unit)
        runCurrent()
        assertFalse(finishing)
        assertEquals(2, searches)
    }
}
