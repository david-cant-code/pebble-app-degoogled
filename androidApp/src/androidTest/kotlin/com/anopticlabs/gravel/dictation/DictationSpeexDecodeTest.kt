package com.anopticlabs.gravel.dictation

import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_FRAME_SAMPLES
import com.anopticlabs.gravel.watchapp.WATCH_SPEEX_SAMPLE_RATE
import com.anopticlabs.gravel.watchapp.decodeWatchSpeex
import coredevices.pebble.services.HybridTranscription
import coredevices.pebble.services.RebbleAsrTranscription
import coredevices.pebble.services.STTRouter
import coredevices.pebble.ui.FakePebbleAccount
import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.mp.KoinPlatform
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Dictation's Speex decode against the codec's native library, and the refusal at both decode sites built from the app's graph. */
@OptIn(ExperimentalUnsignedTypes::class)
class DictationSpeexDecodeTest {

    // A header byte, a 160-bit narrowband frame (mode 3), then a 36-bit wideband layer (submode 1) and 4 pad bits: the
    // layers of a mono watch's 9.8 kbps frame (coredevices/PebbleOS 6e85f0be fw/services/voice/voice_speex.c
    // voice_speex_init; xiph/speex 05895229 libspeex/sb_celp.c sb_encoder_ctl SPEEX_SET_BITRATE and
    // libspeex/modes_wb.c sb_wb_mode). The payload bits are arbitrary, so it decodes to noise, not silence.
    private val noise = ubyteArrayOf(
        0x00u, 0x1Bu, 0xA5u, 0x3Cu, 0x96u, 0xE1u, 0x5Au, 0xC3u, 0x69u, 0x0Fu, 0xF0u,
        0x78u, 0x2Du, 0xB4u, 0x4Bu, 0xD2u, 0x87u, 0x1Eu, 0xE1u, 0x3Cu, 0xA5u,
        0x9Au, 0x5Cu, 0x3Eu, 0xA7u, 0x10u,
    )
    // The header byte alone, which the decoder rejects.
    private val rejected = ubyteArrayOf(0x00u)
    private val frameBytes = WATCH_SPEEX_FRAME_SAMPLES * Short.SIZE_BYTES
    private val refused = TranscriptionResult.Error("Unsupported Speex frame size or sample rate")

    private fun session(sampleRate: Long = 16_000, frameSize: Int) =
        VoiceEncoderInfo.Speex(sampleRate = sampleRate, version = "1.2.1", bitRate = 9_800, bitstreamVersion = 4, frameSize = frameSize)

    private fun soundIn(pcm: ByteArray, from: Int, to: Int) = pcm.copyOfRange(from, to).any { it != 0.toByte() }

    @Test
    fun theDecoderWritesWatchSpeexFrameSamplesAFrame() {
        val pcm = ByteArray(4 * frameBytes)
        val result = SpeexCodec(WATCH_SPEEX_SAMPLE_RATE.toLong(), 9_800, WATCH_SPEEX_FRAME_SAMPLES).use {
            it.decodeFrame(noise.asByteArray(), pcm, hasHeaderByte = true)
        }
        assertEquals(SpeexDecodeResult.Success, result)
        assertTrue(soundIn(pcm, frameBytes - 80, frameBytes), "the frame's last 40 samples are silent")
        assertTrue(!soundIn(pcm, frameBytes, pcm.size), "the decoder wrote past its frame")
    }

    @Test
    fun aSessionDeclaringSmallerFramesDecodesWholeFrames() {
        val pcm = decodeWatchSpeex(session(frameSize = 160), listOf(noise, noise))
        for (i in 0..1) {
            assertTrue(soundIn(pcm, (i + 1) * frameBytes - 80, (i + 1) * frameBytes), "frame $i ends in silence")
        }
    }

    @Test
    fun hybridTranscriptionRefusesBeforeReadingTheRecording() = runBlocking {
        val hybrid = KoinPlatform.getKoin().get<HybridTranscription>()
        for (info in listOf(session(frameSize = 160), session(sampleRate = 8_000, frameSize = 320))) {
            val recording = flow<UByteArray> { fail("recording read for $info") }
            assertEquals(refused, hybrid.transcribe(info, recording, isNotificationReply = false))
        }
    }

    @Test
    fun theRoutersLocalLegRefusesTheSessionBeforeDecoding() = runBlocking {
        val koin = KoinPlatform.getKoin()
        // Signed out, so Rebble answers with an error before any request and RebbleFirst moves on to the local leg.
        val account = FakePebbleAccount()
        assertNull(account.loggedIn.value, "the fake account is signed in")
        val rebble = RebbleAsrTranscription(koin.get(), koin.get(), account, koin.get(), koin.get())
        val rebbleFirst = CoreConfigFlow(MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.RebbleFirst))))
        val router = STTRouter(hybrid = koin.get(), rebble = rebble, whisperService = koin.get(), coreConfigFlow = rebbleFirst)
        assertEquals(refused, router.transcribe(session(frameSize = 160), flowOf(rejected), isNotificationReply = false))
    }
}
