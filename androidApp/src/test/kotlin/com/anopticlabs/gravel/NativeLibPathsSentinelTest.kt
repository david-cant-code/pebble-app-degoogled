package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source sentinels for VerifyNativeLibPaths in androidApp/build.gradle.kts, over the CMake builds the included modules'
 * Gradle files declare: each maps its paths in an add_compile_options call before its first target, and the check is
 * registered for every variant, wired to the merged native libraries, and lists the shared libraries those builds
 * declare as built here.
 */
class NativeLibPathsSentinelTest {

    /** Each CMakeLists.txt an included module's build.gradle.kts names, with its CMake comments removed. */
    private val nativeBuilds by lazy {
        val settings = TrackedTree.file("settings.gradle.kts").readText()
        Regex("""^include\(":([^"]+)"\)""", RegexOption.MULTILINE).findAll(settings).flatMap { include ->
            val module = include.groupValues[1].replace(':', '/')
            val gradle = TrackedTree.file("$module/build.gradle.kts")
            val text = if (gradle.isFile) gradle.readText() else ""
            assertFalse("ndkBuild" in text, "$module declares an ndkBuild build, which this sentinel does not read")
            cmakePath.findAll(text).map { "$module/${it.groupValues[1]}" }
        }.associateWith { withoutComments(TrackedTree.file(it).readText()) }
    }

    private val cmakePath = Regex("""externalNativeBuild\s*\{\s*cmake\s*\{[^}]*?path\s*=\s*file\("([^"]+)"\)""")

    private fun withoutComments(cmake: String) =
        cmake.replace(Regex("""#\[(=*)\[.*?]\1]""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("#[^\n]*"), "")

    @Test
    fun everyNativeBuildMapsItsPathsBeforeItsFirstTarget() {
        assertTrue(nativeBuilds.isNotEmpty(), "no module declares a native build")
        for ((cmake, text) in nativeBuilds) {
            val firstTarget = assertNotNull(
                Regex("""^\s*(add_subdirectory|add_library|add_executable)\(""", RegexOption.MULTILINE).find(text)?.range?.first,
                "$cmake builds no target",
            )
            val options = Regex("""add_compile_options\(([^)]*)\)""").findAll(text.substring(0, firstTarget))
                .joinToString(" ") { it.groupValues[1] }
            for (root in listOf("CMAKE_SOURCE_DIR", "CMAKE_BINARY_DIR", "CMAKE_ANDROID_NDK")) {
                assertTrue("\"-ffile-prefix-map=\${$root}=" in options, "$cmake does not map \${$root} before its first target")
            }
        }
    }

    @Test
    fun theCheckRunsForEveryVariantAndListsTheSharedLibrariesBuiltHere() {
        val build = TrackedTree.file("androidApp/build.gradle.kts").readText()
        assertTrue(
            Regex("""onVariants\s*\{\s*variant\s*->\s*val\s+\w+\s*=\s*tasks\.register<VerifyNativeLibPaths>""").containsMatchIn(build),
            "VerifyNativeLibPaths is no longer registered for every variant",
        )
        assertTrue(
            Regex("""wiredWith\(VerifyNativeLibPaths::nativeLibs\)\s*\.toListenTo\(com\.android\.build\.api\.artifact\.SingleArtifact\.MERGED_NATIVE_LIBS\)""")
                .containsMatchIn(build),
            "VerifyNativeLibPaths is no longer wired to the merged native libraries",
        )
        val builtHere = assertNotNull(
            Regex("""builtHere\.set\(\s*setOf\(([^)]*)\)""").find(build)?.groupValues?.get(1),
            "androidApp/build.gradle.kts no longer sets builtHere",
        )
        val libraries = nativeBuilds.values.flatMap { text ->
            Regex("""add_library\(\s*(\w+)\s+(SHARED|MODULE)\b""").findAll(text).map { "lib${it.groupValues[1]}.so" }
        }
        assertTrue(libraries.isNotEmpty(), "the native builds declare no shared library")
        for (library in libraries) assertTrue("\"$library\"" in builtHere, "builtHere does not name $library: $builtHere")
    }
}
