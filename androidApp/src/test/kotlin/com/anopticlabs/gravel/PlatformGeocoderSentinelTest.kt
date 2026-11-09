package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Place lookups come from the built-in town list (`BuiltInPlaces` in `:pebble`). Android's
 * geocoder is answered by whichever system app provides it (android16-release,
 * core/res/res/values/config.xml, config_enableGeocoderOverlay), which can send the lookup
 * online, so it may not come back, and compass is held to the two artifacts that hold its
 * interfaces and no geocoder of their own (compass 3.2.0). Covers tracked Kotlin, Java, Gradle and catalog files outside iosApp and the
 * engine submodules; a dependency that pulls a geocoder in transitively is not covered.
 */
class PlatformGeocoderSentinelTest {

    private val sources by lazy {
        TrackedTree.files
            .filter { path -> path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".kts") || path.endsWith(".toml") }
            .filter { path -> !path.startsWith("iosApp/") && TrackedTree.submodulePaths.none { path.startsWith("$it/") } }
            .filter { path -> path != SELF }
    }

    @Test
    fun noSourceUsesAPlatformOrOnlineGeocoder() {
        assertTrue(sources.any { it.endsWith("/coredevices/pebble/watchModule.kt") }, "the source list misses watchModule.kt")
        val forbidden = Regex(
            """android\.location\.(Geocoder|\*)|\bMobileGeocoder\b|compass\.(geocoder|autocomplete)\.mobile|""" +
                """\bAutocomplete\.mobile\b|\bGeocoder\.mobile\b""",
        )
        val hits = sources.flatMap { path ->
            TrackedTree.file(path).readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "$path:${index + 1}: ${line.trim()}" else null
            }
        }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun compassIsLimitedToItsInterfaceArtifacts() {
        val lines = TrackedTree.file("gradle/libs.versions.toml").readLines().filter { "jordond" in it }
        val artifacts = lines.map { line ->
            val name = Regex(""""dev\.jordond\.compass:([^"]+)"""").find(line)
                ?: Regex("""group\s*=\s*"dev\.jordond\.compass"\s*,\s*name\s*=\s*"([^"]+)"""").find(line)
            name?.groupValues?.get(1) ?: "unreadable: $line"
        }
        assertEquals(listOf("geocoder", "autocomplete"), artifacts)
        val direct = sources.filter { it.endsWith(".kts") }.filter { "jordond" in TrackedTree.file(it).readText() }
        assertEquals(emptyList(), direct, "compass is declared outside the version catalog")
    }

    private companion object {
        const val SELF = "androidApp/src/test/kotlin/com/anopticlabs/gravel/PlatformGeocoderSentinelTest.kt"
    }
}
