package coredevices.coreapp.indexfeed

import com.russhwolf.settings.MapSettings
import coredevices.coreapp.di.RingPreferences
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.DeviceChoice
import coredevices.util.onboardingDeviceChoice
import coredevices.util.setOnboardingDeviceChoice
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IndexRecordingExportTest {
    @Test
    fun emptyArchiveHasManifestOnly() {
        val entries = unzip(exportRecordingsZip(emptyList()))
        assertEquals(setOf("manifest.json", "manifest.csv", "transcripts.txt"), entries.keys)
        val recordings = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString())
            .jsonObject.getValue("recordings").jsonArray
        assertEquals(0, recordings.size)
        assertEquals(
            "folder,recordedAtEpochSeconds,durationMillis,ringId,iteration,collectionIndex,buttonSequence,transcriptionStatus,transcription\n",
            entries.getValue("manifest.csv").decodeToString(),
        )
        assertEquals("No recordings.\n", entries.getValue("transcripts.txt").decodeToString())
    }

    @Test
    fun unicodeTranscriptRoundTripsThroughTheZip() {
        val text = "こんにちは café — \"quotes\", commas"
        val row = sample(transcript = text, audio = byteArrayOf(1, 2, 3, 4))
        val entries = unzip(exportRecordingsZip(listOf(row)))
        val folder = folderName(row)
        val manifest = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString())
            .jsonObject.getValue("recordings").jsonArray.single().jsonObject
        assertEquals(folder, manifest.getValue("folder").jsonPrimitive.content)
        assertEquals(text, manifest.getValue("transcription").jsonPrimitive.content)
        assertEquals("Done", manifest.getValue("transcriptionStatus").jsonPrimitive.content)
        assertEquals("audio.wav", manifest.getValue("audioFile").jsonPrimitive.content)
        assertEquals("aabb", manifest.getValue("ringId").jsonPrimitive.content)
        assertEquals(text, entries.getValue("$folder/transcript.txt").decodeToString())
        assertTrue(entries.getValue("$folder/audio.wav").contentEquals(byteArrayOf(1, 2, 3, 4)))
        val metadata = Json.parseToJsonElement(entries.getValue("$folder/metadata.json").decodeToString()).jsonObject
        assertEquals(text, metadata.getValue("transcription").jsonPrimitive.content)
        val csv = entries.getValue("manifest.csv").decodeToString()
        assertTrue(csv.contains("\"こんにちは café — \"\"quotes\"\", commas\""))
        assertTrue(entries.getValue("transcripts.txt").decodeToString().contains(text))
    }

    @Test
    fun missingAudioOmitsTheWavAndLeavesTheManifest() {
        val row = sample(transcript = null, audio = null, state = TranscriptState.NotTranscribed)
        val entries = unzip(exportRecordingsZip(listOf(row)))
        val folder = folderName(row)
        assertFalse(entries.keys.any { it.endsWith("audio.wav") })
        val manifest = Json.parseToJsonElement(entries.getValue("manifest.json").decodeToString())
            .jsonObject.getValue("recordings").jsonArray.single().jsonObject
        assertEquals(JsonNull, manifest.getValue("audioFile"))
        assertEquals("NotTranscribed", manifest.getValue("transcriptionStatus").jsonPrimitive.content)
        assertTrue(entries.containsKey("$folder/metadata.json"))
        assertEquals("", entries.getValue("$folder/transcript.txt").decodeToString())
    }

    @Test
    fun exportArchiveIncludesTheStoredWav() = runTest {
        val dir = Files.createTempDirectory("index-export").toString()
        val store = FileIndexRecordingStore(Path(dir))
        val inserted = store.accept(incoming())
        val saved = (inserted as AcceptResult.Inserted).recording
        val viewModel = IndexFeedViewModel(store, IndexTranscriptionPipeline(store, ExportTranscriber(), this), ExportPlayer())
        val entries = unzip(viewModel.exportArchive())
        val wav = entries.entries.single { it.key.endsWith("/audio.wav") }.value
        assertTrue(wav.contentEquals(readFileBytes(saved.audioPath)))
        java.io.File(saved.audioPath).delete()
        val again = unzip(viewModel.exportArchive())
        assertFalse(again.keys.any { it.endsWith("audio.wav") })
    }
}

class RingPreferencesChoiceTest {
    @Test
    fun pairingPromotesWatchToBothAndTurnsTheFeedOn() {
        val settings = MapSettings()
        val config = CoreConfigHolder(CoreConfig(), settings, Json { ignoreUnknownKeys = true })
        val prefs = RingPreferences(settings, config)
        settings.setOnboardingDeviceChoice(DeviceChoice.Watch)
        assertFalse(config.config.value.enableIndex)
        prefs.setRingPaired("AA:BB")
        assertEquals(DeviceChoice.Both, settings.onboardingDeviceChoice())
        assertTrue(config.config.value.enableIndex)
        config.update(config.config.value.withIndexFeedEnabled(false))
        prefs.setRingPaired(null)
        assertEquals(DeviceChoice.Both, settings.onboardingDeviceChoice())
        assertFalse(config.config.value.enableIndex)
        settings.setOnboardingDeviceChoice(DeviceChoice.Index01)
        prefs.setRingPaired("CC:DD")
        assertEquals(DeviceChoice.Index01, settings.onboardingDeviceChoice())
        assertTrue(config.config.value.enableIndex)
    }
}

private fun sample(
    transcript: String?,
    audio: ByteArray?,
    state: TranscriptState = TranscriptState.Done,
) = ExportRecording(
    ringId = "aabb",
    iteration = 0,
    collectionIndex = 3,
    recordedAtEpochSeconds = 1_700_000_000,
    durationMillis = 1500,
    buttonSequence = "short short",
    state = state,
    transcript = transcript,
    audio = audio,
)

private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            out[entry.name] = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return out
}

private class ExportTranscriber : IndexTranscriber {
    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int) = IndexTranscript.Text("")
}

private class ExportPlayer : IndexAudioPlayer {
    override val playingPath = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun toggle(path: String) {}
    override fun stop() {}
}
