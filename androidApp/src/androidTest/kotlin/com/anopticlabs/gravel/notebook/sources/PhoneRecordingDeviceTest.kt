package com.anopticlabs.gravel.notebook.sources

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.notebook.capture.MAX_RECORDING_SAMPLES
import com.anopticlabs.gravel.notebook.capture.RecordingBuffer
import com.anopticlabs.gravel.notebook.capture.WAV_HEADER_BYTES
import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone recording path against the platform: the WAV the recorder stores goes through the
 * drain's decoder unchanged, a recording at the length limit is not refused as too long, the
 * microphone opens in the capture format and delivers samples, and a directory can be synced the
 * way `QueueStore` syncs the one holding kept recordings.
 */
class PhoneRecordingDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun wavOf(samples: ShortArray): ByteArray = RecordingBuffer().apply { append(samples, samples.size) }.toWav()

    @Test
    fun theRecordersWavDecodesToTheSamplesItHolds() {
        val tone = ShortArray(2 * CAPTURE_SAMPLE_RATE) { (8_000 * sin(2 * PI * 440 * it / CAPTURE_SAMPLE_RATE)).toInt().toShort() }
        val wav = wavOf(tone)

        val pcm = MediaCodecAudioDecoder().decode(wav)

        assertContentEquals(wav.copyOfRange(WAV_HEADER_BYTES, wav.size), pcm)
    }

    @Test
    fun aRecordingAtTheLengthLimitIsNotRefused() {
        val pcm = MediaCodecAudioDecoder().decode(wavOf(ShortArray(MAX_RECORDING_SAMPLES)))
        assertEquals(MAX_RECORDING_SAMPLES * 2, pcm.size)
    }

    @Test
    fun aDirectoryOpensForASync() {
        val dir = File(context.cacheDir, "sync-probe").apply { mkdirs() }
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally {
            dir.delete()
        }
    }

    @Test
    fun theMicrophoneStartsInTheCaptureFormatAndDeliversSamples() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val capture = AudioRecordMicrophone(context).start()
        var read = 0
        capture.use {
            val chunk = ShortArray(1_600)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (read < CAPTURE_SAMPLE_RATE && System.nanoTime() < deadline) {
                val n = it.read(chunk)
                assertTrue(n >= 0, "read returned $n")
                read += n
            }
        }
        assertTrue(read >= CAPTURE_SAMPLE_RATE, "one second of samples within three, got $read")
    }
}
