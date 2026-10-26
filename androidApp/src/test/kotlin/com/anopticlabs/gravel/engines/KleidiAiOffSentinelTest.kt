package com.anopticlabs.gravel.engines

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertTrue

/** Each engine's CMakeLists.txt forces GGML_CPU_KLEIDIAI off before it adds its engine (see whisper-native's for why). */
class KleidiAiOffSentinelTest {

    @Test
    fun everyEngineForcesKleidiAiOffBeforeAddingItsEngine() {
        val engines = mapOf(
            "whisper-native/src/main/cpp/CMakeLists.txt" to "whisper.cpp",
            "llm-native/src/main/cpp/CMakeLists.txt" to "llama.cpp",
        )
        for ((cmake, engine) in engines) {
            val text = TrackedTree.file(cmake).readText().replace(Regex("#[^\n]*"), "")
            val off = text.indexOf("""set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)""")
            val added = text.indexOf("add_subdirectory($engine)")
            assertTrue(off in 0 until added, "$cmake does not force GGML_CPU_KLEIDIAI off before add_subdirectory($engine)")
        }
    }
}
