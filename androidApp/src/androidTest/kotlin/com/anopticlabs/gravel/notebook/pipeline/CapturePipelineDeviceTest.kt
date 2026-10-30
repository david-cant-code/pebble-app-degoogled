package com.anopticlabs.gravel.notebook.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.app.NotebookSpeech
import com.anopticlabs.gravel.app.NotebookServerSpeech
import com.anopticlabs.gravel.app.WhisperPhoneSpeech
import com.anopticlabs.gravel.app.NotebookText
import com.anopticlabs.gravel.llm.isLlmSupported
import com.anopticlabs.gravel.models.InferenceExclusion
import com.anopticlabs.gravel.models.TextGenerator
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarmScheduler
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.PostedSetNotice
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.storage.appKeyCrypto
import com.anopticlabs.gravel.transcription.ServerDictationHold
import coredevices.coreapp.testsupport.NoopAnalytics
import coredevices.coreapp.testsupport.ReadOnlyModelPathProvider
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.WhisperTranscriptionService
import coredevices.whisper.isWhisperSupported
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The capture pipeline on the real engines, codecs and Keystore: a queued recording is decoded,
 * transcribed, answered by the installed text model and published, in a throwaway Notebook
 * directory with alarms and notifications stubbed out. Uses the installed base-en model and the
 * largest installed text model, and never downloads. Run on its own, against a persistent install:
 *   adb shell am instrument -w \
 *     -e class com.anopticlabs.gravel.notebook.pipeline.CapturePipelineDeviceTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class CapturePipelineDeviceTest {

    private companion object {
        const val TAG = "CapturePipelineDevice"
        const val SPEECH_MODEL = "whisper-base-en"

        /** base.en hears "Remind me in 30 minutes to send out more ring invites." */
        const val REMINDER_CLIP = "eval_reminder_30min.raw"

        /** base.en hears "Text Eric, the kids have breaded shrimp. Period. We need raw shrimp. ..." */
        const val NOTE_CLIP = "eval_text_eric_shrimp.raw"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "notebook-pipeline-test-${System.nanoTime()}")
    private val crypto = appKeyCrypto()
    private val queue = QueueStore(root, crypto)
    private val notes = NoteStore(root, crypto)
    private val reminders = ReminderStore(root, crypto)
    private val completions = VoiceCompletionStore(root, crypto)
    private val rows = CaptureRows()
    private val armed = mutableListOf<ReminderItem>()
    private var generator: TextGenerator? = null

    @After
    fun cleanUp() {
        runBlocking { generator?.release() }
        root.deleteRecursively()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("[$TAG] $line")
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private val scheduler = object : ReminderAlarmScheduler {
        override fun scheduleFire(item: ReminderItem) {
            armed += item
        }
        override fun cancelFire(id: Int) = Unit
        override fun scheduleSnooze(key: ReminderEventKey, message: String, fireAtMillis: Long) = Unit
        override fun cancelSnooze(key: ReminderEventKey) = Unit
        override fun scheduleLapseSweep(atMillis: Long) = Unit
        override fun cancelLapseSweep() = Unit
    }

    private val notifier = object : ReminderNotifier {
        override fun postFire(key: ReminderEventKey, message: String) = true
        override fun cancelFire(key: ReminderEventKey) = Unit
        override fun cancelFires(id: Int) = Unit
        override fun postedFires() = emptyList<ReminderEventKey>()
        override fun postMissedWhileOff(missed: List<ReminderEvent>) = Unit
        override fun postSetNotice(item: ReminderItem) = Unit
        override fun cancelSetNotice(id: Int) = Unit
        override fun postedSetNotices() = emptyList<PostedSetNotice>()
        override fun postCompletionsToReview(count: Int) = Unit
        override fun cancelCompletionsToReview() = Unit
    }

    private val settings = object : ReminderSettings {
        override val swipeSnoozeMinutes = 10
        override val historyRetentionDays = 90
    }

    private suspend fun drain(captureSettings: CaptureSettings = CaptureSettings()): CaptureDrain {
        Assume.assumeTrue("engines unsupported on this device", isWhisperSupported() && isLlmSupported())
        val provider = ReadOnlyModelPathProvider(File(context.filesDir, "models"), SPEECH_MODEL)
        Assume.assumeTrue("$SPEECH_MODEL not installed; tests never download", provider.isModelDownloaded(SPEECH_MODEL))
        val service = WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(
                MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = SPEECH_MODEL))),
            ),
            modelProvider = provider,
            analytics = NoopAnalytics,
        )
        withTimeout(2.minutes) { while (!service.isModelReady) delay(200) }
        val text = TextGenerator(provider, { null }, { null }, service.dictationPriority, InferenceExclusion())
        generator = text
        val model = text.installedModel()
        Assume.assumeTrue("no text model installed; tests never download", model != null)
        log("text model ${model!!.id}")
        val noServer = object : NotebookServerSpeech {
            override fun configured() = false
            override fun settingsStamp() = 0
            override fun allowedOnCurrentNetwork() = false
            override fun networkConnected() = false
            override suspend fun transcribe(pcm16: ByteArray): String = error("no server in this test")
        }
        val speech = NotebookSpeech({ CactusSTTMode.LocalOnly }, WhisperPhoneSpeech(service), noServer, ServerCheck(), ServerDictationHold())
        val backend = OnDeviceCaptureBackend(MediaCodecAudioDecoder(), speech, NotebookText(text), { false })
        val publisher = CapturePublisher(
            notes, reminders, completions,
            ReminderAlarms(reminders, ReminderEventStore(root, crypto), scheduler, notifier, settings, ReminderRefreshSignal()),
            notifier, ReminderRefreshSignal(), quickRemindMinutes = { 30 }, appBackgrounded = { true },
        )
        return CaptureDrain(queue, notes, backend, publisher, rows, { captureSettings })
    }

    private suspend fun enqueue(id: String, bytes: ByteArray, name: String, at: Long = System.currentTimeMillis()) {
        assertTrue(queue.enqueue(id, bytes, name, at))
    }

    /** A 16 kHz mono clip decodes from a WAV file to exactly its own samples. */
    @Test
    fun aWavRecordingDecodesToItsOwnSamples() {
        val raw = asset(NOTE_CLIP)
        assertContentEquals(raw, MediaCodecAudioDecoder().decode(wav(raw)))
    }

    private fun assertFlagged(audio: ByteArray, reason: String, code: CaptureFailureReason = CaptureFailureReason.UNREADABLE_AUDIO) {
        val failure = runCatching { MediaCodecAudioDecoder().decode(audio) }.exceptionOrNull()
        val refused = failure as? CaptureProcessingException ?: throw AssertionError("not refused as expected: $failure")
        log("refused: ${refused.message}")
        assertEquals(CaptureFailureKind.FLAG, refused.kind)
        assertTrue(refused.message.orEmpty().contains(reason), "unexpected reason: ${refused.message}")
        assertEquals(code, refused.reason)
    }

    @Test
    fun aRecordingOverFiveMinutesIsRefused() {
        assertFlagged(wav(ByteArray((MAX_CAPTURE_MILLIS / 1000 + 1) * CAPTURE_SAMPLE_RATE * 2)), "longer than", CaptureFailureReason.TOO_LONG)
    }

    @Test
    fun aRecordingSampledAbove48kHzIsRefused() {
        assertFlagged(wav(ByteArray(96_000 * 2), rate = 96_000), "sample rate")
    }

    @Test
    fun aFileThatIsNotAudioIsRefused() {
        assertFlagged(ByteArray(4096) { (it * 31).toByte() }, "not readable")
    }

    @Test
    fun anAacRecordingDecodesToTheClipsLength() {
        val raw = asset(REMINDER_CLIP)
        val decoded = MediaCodecAudioDecoder().decode(m4a(raw))
        log("aac: ${raw.size / 2} samples in, ${decoded.size / 2} out")
        assertTrue(abs(decoded.size - raw.size) < raw.size / 20, "decoded ${decoded.size} bytes of ${raw.size}")
    }

    @Test
    fun aSpokenNoteIsSavedWithTheTranscriptAsItsBody() = runBlocking<Unit> {
        val drain = drain()
        enqueue("note", wav(asset(NOTE_CLIP)), "note.wav")
        val report = withTimeout(10.minutes) { drain.drain() }
        log("report $report, rows ${rows.items.value}")
        assertEquals(DrainReport(published = 1), report)
        val id = notes.readMetadata(strict = true).keys.single()
        val body = notes.readNote(id, strict = true)!!
        log("note $id:\n$body")
        assertTrue(body.lowercase().contains("shrimp"), "the body is not the transcript")
        assertTrue(queue.readQueue(strict = true).isEmpty())
    }

    /** Relative time is read against the moment of the recording, here ten minutes before the drain. */
    @Test
    fun aSpokenReminderIsArmedFromTheTimeOfTheRecording() = runBlocking<Unit> {
        val drain = drain()
        val recordedAt = System.currentTimeMillis() - 10 * 60_000L
        enqueue("reminder", m4a(asset(REMINDER_CLIP)), "reminder.m4a", recordedAt)
        val report = withTimeout(10.minutes) { drain.drain() }
        log("report $report, rows ${rows.items.value}")
        assertEquals(DrainReport(published = 1), report)
        val stored = reminders.readAll(strict = true).single()
        log("reminder '${stored.message}' at +${(stored.remindAtMillis - recordedAt) / 1000} s from the recording")
        assertEquals(listOf(stored), armed)
        assertTrue(abs(stored.remindAtMillis - (recordedAt + 30 * 60_000L)) <= 60_000L, "not 30 minutes after the recording")
    }

    @Test
    fun aTriggerWordCaptureIsParked() = runBlocking<Unit> {
        val drain = drain(CaptureSettings(triggerEnabled = true, triggerWord = "text"))
        enqueue("claim", wav(asset(NOTE_CLIP)), "claim.wav")
        assertEquals(DrainReport(published = 1), withTimeout(10.minutes) { drain.drain() })
        val parked = completions.readAll(strict = true).single()
        log("parked '${parked.transcript}'")
        assertTrue(parked.transcript.lowercase().contains("shrimp"))
        assertTrue(notes.readMetadata(strict = true).isEmpty())
    }

    private fun wav(pcm16: ByteArray, rate: Int = CAPTURE_SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm16.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm16.size)
        return header.array() + pcm16
    }

    /** [pcm16] encoded as AAC in an MP4 container. */
    private fun m4a(pcm16: ByteArray): ByteArray {
        val out = File(context.cacheDir, "capture-test.m4a")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, CAPTURE_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            var track = -1
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val count = minOf(buffer.capacity(), pcm16.size - offset)
                        buffer.put(pcm16, offset, count)
                        val timeUs = offset / 2 * 1_000_000L / CAPTURE_SAMPLE_RATE
                        offset += count
                        inputDone = offset >= pcm16.size
                        codec.queueInputBuffer(index, 0, count, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (index >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
        }
        return out.readBytes().also { out.delete() }
    }
}
