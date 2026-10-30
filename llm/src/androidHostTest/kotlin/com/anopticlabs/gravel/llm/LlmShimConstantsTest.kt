package com.anopticlabs.gravel.llm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Pins the shim's output byte cap to the bound the client reads: the shim
 * stops a generation at its cap, and a cap above [LlmEngineLimits.MAX_TEXT_BYTES]
 * would let a generation run to completion only to be refused as an
 * oversized reply. The two values live in two languages, so this reads
 * the constant out of the C++ source.
 */
class LlmShimConstantsTest {

    @Test
    fun theShimsOutputByteCapIsTheClientsTextBound() {
        val source = shimSource().readText()
        val match = assertNotNull(
            Regex("""constexpr size_t kMaxOutputBytes = (\d+)u \* (\d+)u;""").find(source),
            "kMaxOutputBytes not found in llm_jni.cpp in the expected form",
        )
        val (a, b) = match.destructured
        assertEquals(LlmEngineLimits.MAX_TEXT_BYTES, a.toInt() * b.toInt())
    }

    private fun shimSource(): File {
        val relative = "llm-native/src/main/cpp/llm_jni.cpp"
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("$relative not found above ${System.getProperty("user.dir")}")
    }
}
