package com.anopticlabs.gravel.notebook.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.anopticlabs.gravel.notebook.pipeline.CAPTURE_SAMPLE_RATE

/**
 * The phone microphone through AudioRecord, mono PCM16 at [CAPTURE_SAMPLE_RATE], a format every
 * device with a microphone must capture (Android 16 CDD, section 5.4.1, [C-1-1]).
 */
class AudioRecordMicrophone(private val context: Context) : Microphone {

    override fun start(): MicCapture {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO is not granted")
        }
        val minBytes = AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, CHANNEL, ENCODING)
        check(minBytes > 0) { "no buffer size for the capture format: $minBytes" }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, CAPTURE_SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBytes, BUFFER_BYTES))
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone not initialized" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone not recording" }
        } catch (e: Exception) {
            record.release()
            throw e
        }
        return object : MicCapture {
            override fun read(into: ShortArray): Int = record.read(into, 0, into.size)

            override fun close() {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    // Already stopped; release below is what frees the microphone.
                } finally {
                    record.release()
                }
            }
        }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Half a second, so a slow reader does not lose samples. */
        const val BUFFER_BYTES = CAPTURE_SAMPLE_RATE
    }
}
