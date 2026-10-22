@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package coredevices.coreapp.indexfeed

import coredevices.util.AudioEncoding
import coredevices.util.transcription.STTConversationContext
import coredevices.util.transcription.STTLanguage
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.TranscriptionService
import coredevices.util.transcription.TranscriptionSessionStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class IndexTranscriptionPipelineTest {
    @Test
    fun pendingThenTranscribed() = runTest {
        val store = store()
        val id = inserted(store).id
        val transcriber = GateTranscriber()
        val gate = transcriber.prepare()
        val pipeline = IndexTranscriptionPipeline(store, transcriber, this)
        pipeline.enqueue(id)
        testScheduler.advanceUntilIdle()
        assertEquals(TranscriptState.Pending, store.get(id)?.state)
        gate.complete(IndexTranscript.Text("buy milk"))
        testScheduler.advanceUntilIdle()
        val saved = store.get(id)
        assertEquals(TranscriptState.Done, saved?.state)
        assertEquals("buy milk", saved?.transcript)
        assertEquals(1, transcriber.calls)
    }

    @Test
    fun unavailableStaysVisibleForRetry() = runTest {
        val store = store()
        val id = inserted(store).id
        val transcriber = GateTranscriber()
        val first = transcriber.prepare()
        val pipeline = IndexTranscriptionPipeline(store, transcriber, this)
        pipeline.enqueue(id)
        testScheduler.advanceUntilIdle()
        first.complete(IndexTranscript.Unavailable)
        testScheduler.advanceUntilIdle()
        val waiting = store.get(id)
        assertEquals(TranscriptState.NotTranscribed, waiting?.state)
        assertEquals("unavailable", waiting?.detail)
        assertEquals(1, store.recordings.value.size)
        val second = transcriber.prepare()
        pipeline.retry(id)
        testScheduler.advanceUntilIdle()
        assertEquals(TranscriptState.Pending, store.get(id)?.state)
        second.complete(IndexTranscript.Text("got it"))
        testScheduler.advanceUntilIdle()
        assertEquals("got it", store.get(id)?.transcript)
        assertEquals(TranscriptState.Done, store.get(id)?.state)
    }

    @Test
    fun failureThenRetry() = runTest {
        val store = store()
        val id = inserted(store).id
        val transcriber = GateTranscriber()
        val first = transcriber.prepare()
        val pipeline = IndexTranscriptionPipeline(store, transcriber, this)
        pipeline.enqueue(id)
        testScheduler.advanceUntilIdle()
        first.complete(IndexTranscript.Failed("engine"))
        testScheduler.advanceUntilIdle()
        assertEquals("failed", store.get(id)?.detail)
        val second = transcriber.prepare()
        pipeline.retry(id)
        testScheduler.advanceUntilIdle()
        second.complete(IndexTranscript.Text("retried"))
        testScheduler.advanceUntilIdle()
        assertEquals("retried", store.get(id)?.transcript)
    }

    @Test
    fun finishedRecordingIsNotTranscribedAgain() = runTest {
        val store = store()
        val id = inserted(store).id
        store.updateTranscript(id, TranscriptState.Done, "kept", null)
        val transcriber = GateTranscriber()
        val pipeline = IndexTranscriptionPipeline(store, transcriber, this)
        pipeline.enqueue(id)
        testScheduler.advanceUntilIdle()
        assertEquals(0, transcriber.calls)
        assertEquals("kept", store.get(id)?.transcript)
    }

    @Test
    fun serviceMapsDownloadFailureAndSpeech() = runTest {
        val missing = HybridIndexTranscriber(fakeService(available = false) { error("unused") })
        assertIs<IndexTranscript.Unavailable>(missing.transcribe(byteArrayOf(1, 2), 16000))
        val download = HybridIndexTranscriber(
            fakeService { throw TranscriptionException.TranscriptionRequiresDownload("need a model") },
        )
        assertIs<IndexTranscript.Unavailable>(download.transcribe(byteArrayOf(1, 2), 16000))
        val down = HybridIndexTranscriber(
            fakeService { throw TranscriptionException.TranscriptionServiceUnavailable("whisper") },
        )
        assertIs<IndexTranscript.Unavailable>(down.transcribe(byteArrayOf(1, 2), 16000))
        val quiet = HybridIndexTranscriber(
            fakeService { throw TranscriptionException.NoSpeechDetected("empty_result") },
        )
        val empty = assertIs<IndexTranscript.Text>(quiet.transcribe(byteArrayOf(1, 2), 16000))
        assertEquals("", empty.text)
        val ok = HybridIndexTranscriber(fakeService { TranscriptionSessionStatus.Transcription("hello") })
        assertEquals("hello", assertIs<IndexTranscript.Text>(ok.transcribe(byteArrayOf(1, 2), 16000)).text)
        val broken = HybridIndexTranscriber(
            fakeService { throw TranscriptionException.TranscriptionServiceError("boom") },
        )
        assertIs<IndexTranscript.Failed>(broken.transcribe(byteArrayOf(1, 2), 16000))
    }

    private fun TestScope.store() = FileIndexRecordingStore(
        Path(Files.createTempDirectory("index-pipeline").toString()),
        UnconfinedTestDispatcher(testScheduler),
    )

    private suspend fun inserted(store: IndexRecordingStore): IndexRecording {
        val result = store.accept(incoming())
        return assertIs<AcceptResult.Inserted>(result).recording
    }
}

private class GateTranscriber : IndexTranscriber {
    private val gates = ArrayDeque<CompletableDeferred<IndexTranscript>>()
    var calls = 0
        private set

    fun prepare(): CompletableDeferred<IndexTranscript> {
        val gate = CompletableDeferred<IndexTranscript>()
        gates.addLast(gate)
        return gate
    }

    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): IndexTranscript {
        calls += 1
        return gates.removeFirst().await()
    }
}

private fun fakeService(
    available: Boolean = true,
    body: suspend () -> TranscriptionSessionStatus,
): TranscriptionService = object : TranscriptionService {
    override suspend fun isAvailable(): Boolean = available
    override val onInitialized: Channel<Boolean> = Channel(Channel.CONFLATED)
    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        language: STTLanguage,
        conversationContext: STTConversationContext?,
        dictionaryContext: List<String>?,
        contentContext: String?,
        encoding: AudioEncoding,
        initialTimeout: Duration?,
    ): Flow<TranscriptionSessionStatus> = flow { emit(body()) }
}
