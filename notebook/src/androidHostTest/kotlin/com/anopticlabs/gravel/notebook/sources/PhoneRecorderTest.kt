package com.anopticlabs.gravel.notebook.sources

import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.capture.MAX_RECORDING_SAMPLES
import com.anopticlabs.gravel.notebook.capture.WAV_HEADER_BYTES
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.pipeline.CaptureKeepAlive
import com.anopticlabs.gravel.notebook.pipeline.IngestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRecorderTest {

    private fun ByteArray.dataSamples(): Int = ByteBuffer.wrap(this, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int / 2

    private fun ByteArray.sample(i: Int): Short = ByteBuffer.wrap(this, WAV_HEADER_BYTES + 2 * i, 2).order(ByteOrder.LITTLE_ENDIAN).short

    @Test
    fun aStoppedRecordingIsHandedOverWholeAsAWav() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        assertTrue(h.recorder.record())
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        val wav = h.ingested.single()
        assertEquals(20 * 1_600, wav.dataSamples())
        assertEquals(WAV_HEADER_BYTES + 20 * 1_600 * 2, wav.size)
        assertEquals(12_345.toShort(), wav.sample(12_345))
        assertNull(h.recorder.notice.value)
        assertTrue(h.microphone.closed)
    }

    @Test
    fun theLimitEndsTheRecordingAtExactlyItsLengthAndKeepsIt() = runBlocking<Unit> {
        val h = RecorderHarness()
        h.recorder.record()
        h.awaitIdle()
        assertEquals(MAX_RECORDING_SAMPLES, h.ingested.single().dataSamples())
        assertEquals(RecorderNotice.LIMIT_REACHED, h.recorder.notice.value)
    }

    @Test
    fun aRecordingUnderOneSecondIsDiscarded() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 9)
        h.recorder.record()
        h.awaitReads(12)
        h.recorder.stop()
        h.awaitIdle()
        assertTrue(h.ingested.isEmpty())
        assertEquals(RecorderNotice.TOO_SHORT, h.recorder.notice.value)
        assertEquals(listOf("hold", "mic start", "mic close", "release"), h.events.filterNot { it == "preload" })
    }

    @Test
    fun oneSecondIsEnough() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 10)
        h.recorder.record()
        h.awaitReads(12)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(16_000, h.ingested.single().dataSamples())
    }

    @Test
    fun leavingTheAppStopsTheRecordingAndKeepsIt() = runBlocking<Unit> {
        val visibility = AppVisibility()
        val h = RecorderHarness(chunks = 20)
        h.recorder.start(visibility)
        visibility.onStarted()
        visibility.onResumed()
        h.recorder.record()
        h.awaitReads(25)
        visibility.onPaused()
        h.awaitIdle()
        assertEquals(20 * 1_600, h.ingested.single().dataSamples())
    }

    @Test
    fun aRecordingStartedWithNoActivityResumedEndsWhenTheLastActivityStops() = runBlocking<Unit> {
        val visibility = AppVisibility()
        val h = RecorderHarness(chunks = 20)
        h.recorder.start(visibility)
        visibility.onStarted()
        assertTrue(h.recorder.record())
        h.awaitReads(25)
        visibility.onStopped()
        h.awaitIdle()
        assertEquals(20 * 1_600, h.ingested.single().dataSamples())
    }

    @Test
    fun aLostMicrophoneEndsTheRecordingAndKeepsWhatItHad() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20, after = -6)
        h.recorder.record()
        h.awaitIdle()
        assertEquals(20 * 1_600, h.ingested.single().dataSamples())
        assertEquals(RecorderNotice.MIC_LOST, h.recorder.notice.value)
    }

    @Test
    fun aMicrophoneThatCannotStartLeavesNothingHeldOrLoaded() = runBlocking<Unit> {
        val h = RecorderHarness(failStart = SecurityException("no permission"))
        h.recorder.record()
        h.awaitIdle()
        assertEquals(listOf("hold", "mic start", "release"), h.events)
        assertTrue(h.ingested.isEmpty())
        assertEquals(RecorderNotice.NOT_STARTED, h.recorder.notice.value)
    }

    @Test
    fun theRecordingIsHeldFromTheTapUntilTheIngestReturns() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        h.recorder.record()
        assertTrue(h.keepAlive.held)
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(true, h.heldDuringIngest)
        assertEquals("hold", h.events.first())
        assertEquals("release", h.events.last())
        assertTrue(h.events.indexOf("ingest") < h.events.indexOf("release"))
        assertFalse(h.keepAlive.held)
    }

    @Test
    fun oneRecordingAtATime() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        assertTrue(h.recorder.record())
        assertFalse(h.recorder.record())
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(1, h.ingested.size)
        assertEquals(1, h.events.count { it == "hold" })
    }

    @Test
    fun theTextModelLoadsDuringTheRecordingAndItsFailureDoesNotEndIt() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20, preload = { throw IllegalStateException("no text model") })
        h.recorder.record()
        h.awaitReads(25)
        assertTrue("preload" in h.events)
        assertTrue(h.recorder.state.value is RecorderState.Recording)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(1, h.ingested.size)
    }

    @Test
    fun aRecordingTheIngestLostIsReported() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20, result = IngestResult.LOST)
        h.recorder.record()
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(RecorderNotice.NOT_SAVED, h.recorder.notice.value)
    }

    @Test
    fun aStopWhileIdleDoesNotEndTheNextRecording() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        h.recorder.stop()
        h.recorder.record()
        h.awaitReads(25)
        assertTrue(h.recorder.state.value is RecorderState.Recording)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(20 * 1_600, h.ingested.single().dataSamples())
    }

    @Test
    fun theNextRecordingClearsTheLastNotice() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 5)
        h.recorder.record()
        h.awaitReads(7)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(RecorderNotice.TOO_SHORT, h.recorder.notice.value)
        h.recorder.record()
        assertNull(h.recorder.notice.value)
        h.recorder.stop()
        h.awaitIdle()
    }

    @Test
    fun aSecondRecordingAfterAStoppedOneKeepsItsSamples() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        h.recorder.record()
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        h.events.clear()
        val readsBefore = h.microphone.reads.get()
        h.recorder.record()
        h.awaitReads(readsBefore + 25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(2, h.recordings.size)
        assertEquals(20 * 1_600, h.ingested[1].dataSamples())
    }

    @Test
    fun aRecordingIsDatedWhenAndWhereItsMicrophoneStarted() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        h.recorder.record()
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        val recording = h.recordings.single()
        assertEquals(RecorderHarness.START_MILLIS, recording.startedAtMillis)
        assertEquals(TimeZone.of("Asia/Tokyo"), recording.zone)
    }

    @Test
    fun theTextModelIsNotLoadedWhileACaptureRunIsUnderWay() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20, processing = true)
        h.recorder.record()
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertTrue("preload" !in h.events)
        assertEquals(1, h.ingested.size)
    }

    @Test
    fun aKeepAliveThatThrowsNeitherStopsTheRecordingNorStrandsItsState() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20)
        h.keepAlive.failHold = true
        h.keepAlive.failRelease = true
        assertTrue(h.recorder.record())
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(1, h.ingested.size)
        assertEquals(RecorderState.Idle, h.recorder.state.value)
        assertTrue(h.recorder.record())
        h.recorder.stop()
        h.awaitIdle()
    }

    @Test
    fun withNoActivityStartedNothingRecords() = runBlocking<Unit> {
        val visibility = AppVisibility()
        val h = RecorderHarness(chunks = 20)
        h.recorder.start(visibility)
        assertFalse(h.recorder.record())
        assertEquals(RecorderState.Idle, h.recorder.state.value)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun aRecordingEndedUnderASecondByLeavingSaysWhy() = runBlocking<Unit> {
        val visibility = AppVisibility()
        val h = RecorderHarness(chunks = 3)
        h.recorder.start(visibility)
        visibility.onStarted()
        visibility.onResumed()
        h.recorder.record()
        h.awaitReads(5)
        visibility.onPaused()
        h.awaitIdle()
        assertTrue(h.ingested.isEmpty())
        assertEquals(RecorderNotice.INTERRUPTED, h.recorder.notice.value)
    }

    @Test
    fun aSaveThatThrowsIsReportedAndEndsTheRecording() = runBlocking<Unit> {
        val h = RecorderHarness(chunks = 20, onIngest = { throw OutOfMemoryError("test") })
        h.recorder.record()
        h.awaitReads(25)
        h.recorder.stop()
        h.awaitIdle()
        assertEquals(RecorderNotice.NOT_SAVED, h.recorder.notice.value)
        assertFalse(h.keepAlive.held)
    }

    @Test
    fun aRecordingEndedByLeavingHandsItsHoldToProcessingBeforeLettingGo() = runBlocking<Unit> {
        lateinit var coordinator: CaptureCoordinator
        val h = RecorderHarness(chunks = 20, onIngest = {
            coordinator.captureQueued()
            IngestResult.QUEUED
        })
        val processing = object : CaptureKeepAlive {
            override fun hold() {
                h.events += "processing hold"
            }
            override fun extend() = Unit
            override fun release() {
                h.events += "processing release"
            }
        }
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = CaptureCoordinator({ gate.await() }, MutableStateFlow(emptyList()), processing, scope)
        val visibility = AppVisibility()
        coordinator.start(visibility)
        h.recorder.start(visibility)
        visibility.onStarted()
        visibility.onResumed()
        h.recorder.record()
        h.awaitReads(25)
        visibility.onPaused()
        h.awaitIdle()
        assertTrue(h.events.indexOf("processing hold") in 0 until h.events.lastIndexOf("release"), h.events.toString())
        gate.complete(Unit)
        scope.cancel()
    }
}
