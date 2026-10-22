package com.anopticlabs.gravel.whisper

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Pins the shim's long-form bounds to the ones the app side checks: a
 * shim cap above [WhisperLongForm.MAX_REPLY_BYTES] would pack a reply the
 * client refuses, a recording bound that differs would refuse audio the
 * client already sent, and a header size that differs misreads every
 * reply. The two sides are in two languages, so this reads the
 * constants out of the C++ source.
 */
class WhisperShimConstantsTest {

    @Test
    fun theShimsLongFormBoundsAreTheClients() {
        val source = shimSource().readText()
        val samples = assertNotNull(
            Regex("""constexpr int kMaxLongFormCallSamples = (\d+) \* (\d+) \* WHISPER_SAMPLE_RATE;""").find(source),
            "kMaxLongFormCallSamples not found in whisper_jni.cpp in the expected form",
        ).destructured
        assertEquals(
            WhisperLongForm.MAX_CALL_SAMPLES,
            samples.component1().toInt() * samples.component2().toInt() * WhisperLongForm.SAMPLE_RATE,
        )
        val replyHeader = assertNotNull(
            Regex("""constexpr size_t kLongFormReplyHeaderBytes = (\d+);""").find(source),
            "kLongFormReplyHeaderBytes not found in whisper_jni.cpp in the expected form",
        ).destructured
        assertEquals(WhisperLongForm.REPLY_HEADER_BYTES, replyHeader.component1().toInt())
        val reply = assertNotNull(
            Regex("""constexpr size_t kMaxLongFormReplyBytes = (\d+)u \* (\d+)u;""").find(source),
            "kMaxLongFormReplyBytes not found in whisper_jni.cpp in the expected form",
        ).destructured
        assertEquals(WhisperLongForm.MAX_REPLY_BYTES, reply.component1().toInt() * reply.component2().toInt())
        val header = assertNotNull(
            Regex("""constexpr size_t kLongFormSegmentHeaderBytes = (\d+) \+ (\d+) \+ (\d+);""").find(source),
            "kLongFormSegmentHeaderBytes not found in whisper_jni.cpp in the expected form",
        ).destructured
        assertEquals(
            WhisperLongForm.SEGMENT_HEADER_BYTES,
            header.component1().toInt() + header.component2().toInt() + header.component3().toInt(),
        )
    }

    private fun shimSource(): File {
        val relative = "whisper-native/src/main/cpp/whisper_jni.cpp"
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
