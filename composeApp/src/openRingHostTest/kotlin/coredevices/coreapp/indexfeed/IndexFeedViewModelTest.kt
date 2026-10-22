@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package coredevices.coreapp.indexfeed

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class IndexFeedViewModelTest {
    @Test
    fun feedIsNewestFirstAndDeleteRemovesARow() = runTest {
        val store = store()
        store.accept(incoming(index = 1, at = 10))
        store.accept(incoming(index = 2, at = 40))
        val viewModel = IndexFeedViewModel(store, pipeline(store, this), FakePlayer())
        viewModel.ensureLoaded()
        assertEquals(listOf(2, 1), viewModel.recordings.value.map { it.collectionIndex })
        assertEquals("0:01", formatDuration(viewModel.recordings.value.first().durationMillis))
        viewModel.delete(viewModel.recordings.value.first().id)
        assertEquals(listOf(1), viewModel.recordings.value.map { it.collectionIndex })
    }

    @Test
    fun retryUpdatesTheRowInPlace() = runTest {
        val store = store()
        val inserted = assertIs<AcceptResult.Inserted>(store.accept(incoming(index = 7, at = 5)))
        store.updateTranscript(inserted.recording.id, TranscriptState.NotTranscribed, null, "failed")
        val transcriber = FeedTranscriber()
        val gate = transcriber.prepare()
        val viewModel = IndexFeedViewModel(store, IndexTranscriptionPipeline(store, transcriber, this), FakePlayer())
        viewModel.retry(inserted.recording.id)
        testScheduler.advanceUntilIdle()
        assertEquals(TranscriptState.Pending, viewModel.recordings.value.single().state)
        assertFalse(canRetryTranscription(viewModel.recordings.value.single()))
        gate.complete(IndexTranscript.Text("note from the ring"))
        testScheduler.advanceUntilIdle()
        val row = viewModel.recordings.value.single()
        assertEquals(TranscriptState.Done, row.state)
        assertEquals("note from the ring", row.transcript)
        assertEquals("note from the ring", transcriptLabel(row))
    }

    @Test
    fun playbackTogglesAndDeleteStopsIt() = runTest {
        val store = store()
        val inserted = assertIs<AcceptResult.Inserted>(store.accept(incoming()))
        val player = FakePlayer()
        val viewModel = IndexFeedViewModel(store, pipeline(store, this), player)
        viewModel.togglePlayback(inserted.recording.audioPath)
        assertEquals(inserted.recording.audioPath, viewModel.playingPath.value)
        viewModel.togglePlayback(inserted.recording.audioPath)
        assertNull(viewModel.playingPath.value)
        viewModel.togglePlayback(inserted.recording.audioPath)
        viewModel.delete(inserted.recording.id)
        assertNull(viewModel.playingPath.value)
        assertTrue(viewModel.recordings.value.isEmpty())
    }

    private fun TestScope.store() = FileIndexRecordingStore(
        Path(Files.createTempDirectory("index-feed").toString()),
        UnconfinedTestDispatcher(testScheduler),
    )

    private fun pipeline(store: IndexRecordingStore, scope: TestScope) =
        IndexTranscriptionPipeline(store, FeedTranscriber(), scope)
}

private class FakePlayer : IndexAudioPlayer {
    private val playing = MutableStateFlow<String?>(null)
    override val playingPath: StateFlow<String?> = playing
    override fun toggle(path: String) {
        playing.value = if (playing.value == path) null else path
    }
    override fun stop() {
        playing.value = null
    }
}

private class FeedTranscriber : IndexTranscriber {
    private val gates = ArrayDeque<CompletableDeferred<IndexTranscript>>()
    fun prepare(): CompletableDeferred<IndexTranscript> {
        val gate = CompletableDeferred<IndexTranscript>()
        gates.addLast(gate)
        return gate
    }
    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): IndexTranscript =
        gates.removeFirst().await()
}
