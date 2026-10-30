package com.anopticlabs.gravel.engines

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each engine's baseline-arch probe must test every hwcap its engine's
 * compile floor (`GGML_CPU_ARM_ARCH`) implies, since ggml selects CPU
 * features at compile time and the app loads the engine library only after
 * the probe passes. The probe's `? JNI_TRUE : JNI_FALSE` return must be a
 * plain `&&` chain of `(hwcap & HWCAP_*)` tests read from
 * `getauxval(AT_HWCAP)`; comments are ignored, disabled preprocessor
 * branches are not.
 */
class CpuFloorProbeSentinelTest {

    private class Engine(val name: String, val cmake: String, val probe: String)

    private val engines = listOf(
        Engine("whisper", "whisper-native/src/main/cpp/CMakeLists.txt", "whisper-native/src/main/cpp/whisper_cpu.c"),
        Engine("llm", "llm-native/src/main/cpp/CMakeLists.txt", "llm-native/src/main/cpp/llm_cpu.c"),
    )

    // whisper_cpu.c states the source of this mapping.
    private val hwcapsFor = mapOf(
        "armv8.2-a" to listOf("HWCAP_ATOMICS", "HWCAP_ASIMDRDM", "HWCAP_CRC32"),
        "dotprod" to listOf("HWCAP_ASIMDDP"),
        "fp16" to listOf("HWCAP_FPHP", "HWCAP_ASIMDHP"),
    )

    private val floor = Regex("""set\(GGML_CPU_ARM_ARCH "([^"]+)"""")
    private val verdict = Regex("""return\s*\((.*?)\)\s*\?\s*JNI_TRUE\s*:\s*JNI_FALSE\s*;""", RegexOption.DOT_MATCHES_ALL)
    private val term = Regex("""\(hwcap & (HWCAP_[A-Z0-9_]+)\)""")

    /** What [probe] fails to test of the floor in [cmake]; empty when it tests all of it. */
    private fun problems(cmake: String, probe: String): List<String> {
        val arch = floor.find(cmake)?.groupValues?.get(1) ?: return listOf("no GGML_CPU_ARM_ARCH floor found")
        val code = probe.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
        val returns = verdict.findAll(code).toList()
        if (returns.size != 1) return listOf("expected one JNI_TRUE/JNI_FALSE verdict, found ${returns.size}")
        if (!Regex("""unsigned long hwcap = getauxval\(AT_HWCAP\);""").containsMatchIn(code)) {
            return listOf("hwcap is not read from getauxval(AT_HWCAP)")
        }
        val tested = mutableSetOf<String>()
        val found = buildList {
            for (part in returns.single().groupValues[1].split("&&").map { it.trim() }) {
                val match = term.matchEntire(part)
                if (match == null) add("'$part' is not a plain hwcap test") else tested += match.groupValues[1]
            }
        }
        return found + buildList {
            val parts = arch.split('+')
            for (feature in parts) {
                val caps = hwcapsFor[feature]
                if (caps == null) {
                    add(if (feature == parts.first()) "base architecture $feature has no entry in this table" else "+$feature has no entry in this table")
                    continue
                }
                caps.filterNot { it in tested }.forEach { add("the probe does not test $it for $feature") }
            }
        }
    }

    private fun read(path: String) = TrackedTree.file(path).readText()

    @Test
    fun everyProbeTestsItsEnginesWholeFloor() {
        for (engine in engines) {
            assertEquals(emptyList(), problems(read(engine.cmake), read(engine.probe)), engine.name)
        }
    }

    private fun String.edited(old: String, new: String): String {
        assertTrue(contains(old), "the file no longer holds the text this edit replaces: $old")
        return replaceFirst(old, new)
    }

    @Test
    fun knownBadEditsAreNoticed() {
        val cmake = read(engines[0].cmake)
        val probe = read(engines[0].probe)
        val fphp = " && (hwcap & HWCAP_FPHP)"
        val missing = listOf("the probe does not test HWCAP_FPHP for fp16")
        assertEquals(missing, problems(cmake, probe.edited(fphp, "")))
        assertEquals(missing, problems(cmake, probe.edited(fphp, " /* && (hwcap & HWCAP_FPHP) */")))
        assertEquals(
            listOf(
                "'(hwcap & HWCAP_ASIMDDP) || (hwcap & HWCAP_FPHP)' is not a plain hwcap test",
                "the probe does not test HWCAP_ASIMDDP for dotprod",
            ) + missing,
            problems(cmake, probe.edited(fphp, " || (hwcap & HWCAP_FPHP)")),
        )
        assertEquals(
            listOf("'!(hwcap & HWCAP_FPHP)' is not a plain hwcap test") + missing,
            problems(cmake, probe.edited(fphp, " && !(hwcap & HWCAP_FPHP)")),
        )
        assertEquals(
            listOf("hwcap is not read from getauxval(AT_HWCAP)"),
            problems(cmake, probe.edited("getauxval(AT_HWCAP)", "~0UL")),
        )
        assertEquals(
            listOf("+i8mm has no entry in this table"),
            problems(cmake.edited("+dotprod+fp16\"", "+dotprod+fp16+i8mm\""), probe),
        )
        assertEquals(
            listOf("base architecture armv8.6-a has no entry in this table"),
            problems(cmake.edited("\"armv8.2-a+", "\"armv8.6-a+"), probe),
        )
    }
}
