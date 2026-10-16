package com.anopticlabs.gravel.notebook.pipeline

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import coredevices.resampler.Resampler
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a queued recording through the platform's media extractor and codecs, called from the app
 * process and reading the recording from memory, then downmixes it to mono and resamples it to
 * [CAPTURE_SAMPLE_RATE]. A recording longer than [MAX_CAPTURE_MILLIS], or sampled above
 * [MAX_SOURCE_RATE], is refused, never cut.
 */
class MediaCodecAudioDecoder : CaptureAudioDecoder {

    override fun decode(audio: ByteArray): ByteArray {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(ByteArraySource(audio))
            } catch (e: IOException) {
                throw flag("the recording's format is not readable", e)
            } catch (e: IllegalArgumentException) {
                throw flag("the recording's format is not readable", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw flag("the recording has no audio track")
            extractor.selectTrack(track)
            val (mono, rate) = decodeTrack(extractor, extractor.getTrackFormat(track))
            return toPcm16Bytes(resampleTo16k(mono, rate))
        } catch (e: OutOfMemoryError) {
            throw CaptureProcessingException(CaptureFailureKind.RETRY, "not enough memory to decode the recording")
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat): Pair<ShortArray, Int> {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw flag("the recording's track has no format")
        if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) throw flag("the recording reports no sample rate")
        var rate = checkedRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        var channels = checkedChannels(format)
        var encoding = PCM_16BIT
        val declaredMicros = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        if (declaredMicros > MAX_CAPTURE_MILLIS * 1000L) throw tooLong()
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: IOException) {
            throw flag("no decoder for the recording's format", e)
        } catch (e: IllegalArgumentException) {
            throw flag("no decoder for the recording's format", e)
        }
        val mono = MonoPcm(initialSamples(declaredMicros, rate))
        try {
            try {
                codec.configure(format, null, null, 0)
            } catch (e: IllegalArgumentException) {
                throw flag("the decoder refuses the recording's format", e)
            }
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var stalls = 0
            while (true) {
                var progressed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        progressed = true
                        val size = try {
                            extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        } catch (e: IllegalArgumentException) {
                            // Any read error but the end of the stream (android16-release, android_media_MediaExtractor.cpp, readSampleData).
                            throw flag("a sample of the recording could not be read", e)
                        }
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index >= 0) {
                    progressed = true
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(index)!!
                        try {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                        } catch (e: IllegalArgumentException) {
                            throw flag("the decoder's output is out of range", e)
                        }
                        downmixInto(mono, toShorts(out, encoding), channels)
                        if (mono.size > maxSamplesAt(rate)) throw tooLong()
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true
                    val out = codec.outputFormat
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = checkedRate(out.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    channels = checkedChannels(out)
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                if (progressed) {
                    stalls = 0
                } else if (++stalls > MAX_STALLS) {
                    throw CaptureProcessingException(CaptureFailureKind.RETRY, "the audio decoder stopped making progress")
                }
            }
        } catch (e: MediaCodec.CodecException) {
            if (e.isTransient) throw CaptureProcessingException(CaptureFailureKind.RETRY, "the audio decoder failed", e)
            throw flag("the audio decoder failed", e)
        } catch (e: IllegalStateException) {
            throw CaptureProcessingException(CaptureFailureKind.RETRY, "the audio decoder failed", e)
        } finally {
            try {
                codec.stop()
            } catch (e: IllegalStateException) {
                // Never started, or already failed; release below is what frees it.
            }
            codec.release()
        }
        return mono.toArray() to rate
    }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    internal companion object {
        // AudioFormat.ENCODING_PCM_16BIT and ENCODING_PCM_FLOAT, the encodings a decoder reports.
        const val PCM_16BIT = 2
        const val PCM_FLOAT = 4
        private const val TIMEOUT_US = 10_000L
        private const val MAX_STALLS = 500
        private const val MAX_CHANNELS = 8

        /** The highest sample rate taken, which bounds the decoded samples held in memory. */
        const val MAX_SOURCE_RATE = 48_000

        private fun flag(message: String, cause: Throwable? = null, reason: CaptureFailureReason = CaptureFailureReason.UNREADABLE_AUDIO) =
            CaptureProcessingException(CaptureFailureKind.FLAG, message, cause, reason = reason)

        private fun tooLong() = flag("the recording is longer than ${MAX_CAPTURE_MILLIS / 60_000} minutes", reason = CaptureFailureReason.TOO_LONG)

        /** [rate], or a flag for one outside 1..[MAX_SOURCE_RATE]. */
        fun checkedRate(rate: Int): Int =
            if (rate in 1..MAX_SOURCE_RATE) rate else throw flag("the recording's sample rate is not supported")

        private fun checkedChannels(format: MediaFormat): Int {
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            return if (channels in 1..MAX_CHANNELS) channels else throw flag("the recording's channel count is not supported")
        }

        /** Mono samples at [rate] that fit in [MAX_CAPTURE_MILLIS]. */
        fun maxSamplesAt(rate: Int): Long = rate.toLong() * MAX_CAPTURE_MILLIS / 1000

        /** Room for the declared duration, within the cap; a small start when none is declared. */
        fun initialSamples(declaredMicros: Long, rate: Int): Int =
            if (declaredMicros <= 0) MonoPcm.INITIAL
            else minOf(declaredMicros * rate / 1_000_000 + rate, maxSamplesAt(rate) + 1).toInt()

        fun toShorts(frame: ByteBuffer, encoding: Int): ShortArray = when (encoding) {
            PCM_16BIT -> ShortArray(frame.remaining() / 2).also { frame.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
            PCM_FLOAT -> {
                val floats = FloatArray(frame.remaining() / 4).also { frame.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            }
            else -> throw flag("the decoder produced an unsupported sample encoding")
        }

        /** Averages each frame of [channels] interleaved samples; a trailing partial frame is dropped. */
        fun downmixInto(dst: MonoPcm, interleaved: ShortArray, channels: Int) {
            if (channels <= 1) {
                dst.addAll(interleaved)
                return
            }
            var i = 0
            while (i + channels <= interleaved.size) {
                var sum = 0
                for (c in 0 until channels) sum += interleaved[i + c]
                dst.add((sum / channels).toShort())
                i += channels
            }
        }

        fun resampleTo16k(mono: ShortArray, rate: Int): ShortArray {
            if (rate <= 0) throw flag("the recording reports no sample rate")
            if (rate == CAPTURE_SAMPLE_RATE || mono.isEmpty()) return mono
            return Resampler(rate, CAPTURE_SAMPLE_RATE).process(mono)
        }

        fun toPcm16Bytes(samples: ShortArray): ByteArray =
            ByteArray(samples.size * 2).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples) }
    }
}

/** A growable mono PCM16 buffer. */
internal class MonoPcm(initialSamples: Int = INITIAL) {
    private var samples = ShortArray(initialSamples)
    var size = 0
        private set

    fun add(sample: Short) {
        ensure(size + 1)
        samples[size++] = sample
    }

    fun addAll(more: ShortArray) {
        ensure(size + more.size)
        more.copyInto(samples, size)
        size += more.size
    }

    fun toArray(): ShortArray = samples.copyOf(size)

    private fun ensure(needed: Int) {
        if (needed <= samples.size) return
        samples = samples.copyOf(maxOf(needed, samples.size + samples.size / 2))
    }

    companion object {
        const val INITIAL = 16_000 * 10
    }
}
