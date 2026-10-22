@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package coredevices.coreapp.indexfeed

import coredevices.coreapp.di.pcm16LeWav
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

class IndexRecordingStoreTest {
    @Test
    fun sameRingAndIndexIsStoredOnce() = runTest {
        val store = newStore()
        val first = store.accept(incoming(index = 3, at = 100))
        val second = store.accept(incoming(index = 3, at = 200, button = "long"))
        assertIs<AcceptResult.Inserted>(first)
        val duplicate = assertIs<AcceptResult.Duplicate>(second)
        assertEquals(first.recording.id, duplicate.recording.id)
        assertEquals(1, store.recordings.value.size)
        assertEquals("short short", store.recordings.value.single().buttonSequence)
        assertEquals(100, store.recordings.value.single().recordedAtEpochSeconds)
    }

    @Test
    fun ringIdIgnoresColonsAndCase() = runTest {
        val store = newStore()
        store.accept(incoming(ringId = "AA:BB:CC:DD:EE:FF", index = 1))
        val again = store.accept(incoming(ringId = "aa-bb-cc-dd-ee-ff".replace("-", ""), index = 1))
        assertIs<AcceptResult.Duplicate>(again)
    }

    @Test
    fun rePairStartsANewIterationAndKeepsHistory() = runTest {
        val store = newStore()
        store.accept(incoming(index = 1, at = 10))
        store.markTransfersAsPreviousIndexIteration()
        val again = store.accept(incoming(index = 1, at = 20))
        val inserted = assertIs<AcceptResult.Inserted>(again)
        assertEquals(1, inserted.recording.iteration)
        assertEquals(listOf(20L, 10L), store.recordings.value.map { it.recordedAtEpochSeconds })
    }

    @Test
    fun reloadKeepsRecordingsIterationAndAudio() = runTest {
        val dir = Files.createTempDirectory("index-recordings").toString()
        val store = FileIndexRecordingStore(Path(dir), UnconfinedTestDispatcher())
        val inserted = assertIs<AcceptResult.Inserted>(store.accept(incoming(index = 4, at = 50)))
        store.markTransfersAsPreviousIndexIteration()
        store.updateTranscript(inserted.recording.id, TranscriptState.Done, "hello", null)
        val reloaded = FileIndexRecordingStore(Path(dir), UnconfinedTestDispatcher())
        reloaded.ensureLoaded()
        val saved = reloaded.get(inserted.recording.id)
        assertEquals("hello", saved?.transcript)
        assertEquals(TranscriptState.Done, saved?.state)
        val pcm = readWavPcm(saved!!.audioPath)
        assertEquals(32, pcm?.size)
        val next = assertIs<AcceptResult.Inserted>(reloaded.accept(incoming(index = 4, at = 60)))
        assertEquals(1, next.recording.iteration)
        assertEquals(2, reloaded.recordings.value.size)
    }

    @Test
    fun deleteRemovesTheRecordingAndItsAudio() = runTest {
        val store = newStore()
        val inserted = assertIs<AcceptResult.Inserted>(store.accept(incoming()))
        assertTrue(readWavPcm(inserted.recording.audioPath) != null)
        store.delete(inserted.recording.id)
        assertNull(store.get(inserted.recording.id))
        assertTrue(store.recordings.value.isEmpty())
        assertNull(readWavPcm(inserted.recording.audioPath))
    }

    @Test
    fun newestRecordingIsFirst() = runTest {
        val store = newStore()
        store.accept(incoming(index = 1, at = 30))
        store.accept(incoming(index = 2, at = 10))
        store.accept(incoming(index = 3, at = 30))
        assertEquals(listOf(3, 1, 2), store.recordings.value.map { it.collectionIndex })
    }

    @Test
    fun labelsAndDuration() {
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1970-01-01 00:00", formatRecordedAt(0, TimeZone.UTC))
        val pending = sample(state = TranscriptState.Pending)
        assertEquals("Transcribing…", transcriptLabel(pending))
        assertEquals(
            "Not transcribed. Download a speech model in Settings → Speech Recognition → Manage Offline Models, then retry.",
            transcriptLabel(sample(state = TranscriptState.NotTranscribed, detail = "unavailable")),
        )
        assertEquals("Not transcribed", transcriptLabel(sample(state = TranscriptState.NotTranscribed, detail = "failed")))
        assertEquals("hello", transcriptLabel(sample(state = TranscriptState.Done, transcript = "hello")))
        assertEquals("No speech detected", transcriptLabel(sample(state = TranscriptState.Done, transcript = "")))
    }

    private fun newStore() = FileIndexRecordingStore(
        Path(Files.createTempDirectory("index-recordings").toString()),
        UnconfinedTestDispatcher(),
    )

    private fun sample(
        state: TranscriptState,
        transcript: String? = null,
        detail: String? = null,
    ) = IndexRecording(
        id = "id",
        ringId = "aabb",
        iteration = 0,
        collectionIndex = 1,
        recordedAtEpochSeconds = 0,
        durationMillis = 1000,
        buttonSequence = null,
        sampleRate = 16000,
        audioFileName = "a.wav",
        audioPath = "a.wav",
        transcript = transcript,
        state = state,
        detail = detail,
    )
}

internal fun incoming(
    ringId: String = "AA:BB:CC:DD:EE:FF",
    index: Int = 1,
    at: Long = 1_700_000_000,
    button: String? = "short short",
): IncomingRecording = IncomingRecording(
    ringId = ringId,
    collectionIndex = index,
    recordedAtEpochSeconds = at,
    durationMillis = 1000,
    buttonSequence = button,
    sampleRate = 16000,
    wav = pcm16LeWav(ShortArray(16) { 1000 }, 16000),
)
