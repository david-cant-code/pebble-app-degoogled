package coredevices.coreapp

import coredevices.coreapp.FdroidScannerReplica.VersionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Positive controls for [FdroidScannerReplica]: every check is shown to fire
 * on a known-bad sample and stay quiet on a known-good one, so a lost regex
 * prefix, an escaping slip while re-syncing a constant, or a catalog reader
 * that starts resolving every accessor to nothing cannot leave
 * [FdroidGuardrailsTest] green over a tree F-Droid would reject. The samples
 * are the shapes that have actually mattered here (a Crashlytics wrapper
 * behind a catalog accessor, a GitHub Packages publishing URL, a checked-in
 * `.so`) plus the scanner's documented edge cases.
 */
class FdroidScannerReplicaTest {

    // ---- Maven repository URLs -------------------------------------------

    @Test
    fun mavenUrlOffTheAllowlistIsReported() {
        val text = """
            publishing {
                repositories {
                    maven {
                        url = uri("https://maven.pkg.github.com/example/repo")
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("https://maven.pkg.github.com/example/repo"), FdroidScannerReplica.unknownMavenRepos(text))
    }

    @Test
    fun allowlistedMavenUrlsAreNotReported() {
        val text = """
            repositories {
                maven { url = uri("https://jitpack.io") }
                maven { setUrl("https://repo1.maven.org/maven2/") }
                maven { url = uri("file:///usr/share/maven-repo") }
                maven("https://maven.google.com")
            }
        """.trimIndent()
        assertEquals(emptyList(), FdroidScannerReplica.unknownMavenRepos(text))
    }

    @Test
    fun commentedOutMavenUrlsAreIgnoredLikeTheScannerDoes() {
        val text = """
            repositories {
                // maven { url = uri("https://maven.pkg.github.com/example/repo") }
                /* maven {
                     url = uri("https://maven.example.org/private")
                   } */
            }
        """.trimIndent()
        assertEquals(emptyList(), FdroidScannerReplica.unknownMavenRepos(text))
    }

    // ---- Dependency lines ------------------------------------------------

    private val catalog = VersionCatalog.parse(
        """
        [versions]
        crash = "0.9.0" # trailing comment with a # inside "quotes # here"
        gms = "21.0.0"

        [libraries]
        crashkios = { module = "co.touchlab.crashkios:crashlytics", version.ref = "crash" }
        location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "gms" }
        okio = "com.squareup.okio:okio:3.9.0"
        ktor-client-core = { module = "io.ktor:ktor-client-core", version = "3.0.0" }

        [plugins]
        crashlytics-plugin = { id = "com.google.firebase.crashlytics", version = "3.0.0" }

        [bundles]
        tracking = ["crashkios", "okio"]
        """.trimIndent().lines(),
    )

    @Test
    fun literalDependencyLineNamingASuspectIsReported() {
        val hits = FdroidScannerReplica.usualSuspects(
            listOf("""    implementation("com.google.firebase:firebase-analytics:22.0.0")"""),
            VersionCatalog.EMPTY,
        )
        assertEquals(1, hits.size, hits.toString())
        assertTrue(hits.single().contains("firebase"), hits.single())
    }

    @Test
    fun catalogAccessorResolvingToASuspectIsReported() {
        val hits = FdroidScannerReplica.usualSuspects(listOf("        implementation(libs.crashkios)"), catalog)
        assertEquals(1, hits.size, hits.toString())
        assertTrue(hits.single().contains("co.touchlab.crashkios:crashlytics:0.9.0"), hits.single())
    }

    @Test
    fun groupNameCatalogEntryAndPluginAliasAndBundleAreResolved() {
        val lines = listOf(
            "implementation(libs.location)",
            "alias(libs.plugins.crashlytics.plugin)",
            "implementation(libs.bundles.tracking)",
        )
        val hits = FdroidScannerReplica.usualSuspects(lines, catalog)
        // The plugin id trips two signatures (firebase and crashlytics), as it does on the buildserver.
        assertEquals(4, hits.size, hits.toString())
        assertTrue(hits[0].startsWith("line 1:") && hits[0].endsWith("com.google.android.gms:play-services-location:21.0.0"), hits[0])
        assertTrue(hits[1].startsWith("line 2:") && hits[1].endsWith("com.google.firebase.crashlytics:3.0.0"), hits[1])
        assertTrue(hits[2].startsWith("line 2:") && hits[2].endsWith("com.google.firebase.crashlytics:3.0.0"), hits[2])
        assertTrue(hits[3].startsWith("line 3:") && hits[3].endsWith("co.touchlab.crashkios:crashlytics:0.9.0"), hits[3])
    }

    @Test
    fun benignDependencyLinesAreNotReported() {
        val lines = listOf(
            """implementation("com.squareup.okio:okio:3.9.0")""",
            "implementation(libs.okio)",
            "implementation(libs.ktor.client.core)",
            "implementation(project(\":pebble\"))",
            """// implementation("com.google.firebase:firebase-analytics:22.0.0")""",
        )
        // The scanner's line matchers are anchored at the start of the line
        // (optional whitespace, optional quote, then the command), so a line
        // commented out with // is not a dependency line to it either.
        assertEquals(emptyList(), FdroidScannerReplica.usualSuspects(lines, catalog))
    }

    @Test
    fun settingsDeclaringExtraCatalogsIsDetected() {
        assertTrue(FdroidScannerReplica.declaresCatalogsTheReplicaCannotRead("dependencyResolutionManagement { versionCatalogs { create(\"tools\") {} } }"))
        assertTrue(FdroidScannerReplica.declaresCatalogsTheReplicaCannotRead("defaultLibrariesExtensionName = \"deps\""))
        assertFalse(FdroidScannerReplica.declaresCatalogsTheReplicaCannotRead("include(\":androidApp\")"))
    }

    // ---- Version catalog reader -----------------------------------------

    @Test
    fun catalogReaderFailsOnMultiLineArray() {
        val lines = listOf("[bundles]", "tracking = [", "    \"crashkios\",", "]")
        assertFailsWith<AssertionError> { VersionCatalog.parse(lines) }
    }

    @Test
    fun catalogReaderFailsOnMultiLineInlineTable() {
        val lines = listOf("[libraries]", "crashkios = { module = \"co.touchlab.crashkios:crashlytics\",", "  version = \"0.9.0\" }")
        assertFailsWith<AssertionError> { VersionCatalog.parse(lines) }
    }

    @Test
    fun catalogReaderFailsOnNestedVersionTable() {
        val lines = listOf("[libraries]", "okio = { module = \"com.squareup.okio:okio\", version = { strictly = \"3.9.0\" } }")
        assertFailsWith<AssertionError> { VersionCatalog.parse(lines) }
    }

    @Test
    fun catalogReaderFailsOnUnknownVersionRef() {
        val lines = listOf("[libraries]", "okio = { module = \"com.squareup.okio:okio\", version.ref = \"nope\" }")
        assertFailsWith<AssertionError> { VersionCatalog.parse(lines) }
    }

    @Test
    fun catalogReaderResolvesTheSingleLineForms() {
        assertEquals(listOf("com.squareup.okio:okio:3.9.0"), catalog.coordinates("okio"))
        assertEquals(listOf("io.ktor:ktor-client-core:3.0.0"), catalog.coordinates("ktor.client.core"))
        assertEquals(listOf("com.google.firebase.crashlytics:3.0.0"), catalog.coordinates("plugins.crashlytics.plugin"))
        assertEquals(listOf("com.google.firebase.crashlytics:3.0.0"), catalog.coordinates("plugins.crashlytics.plugin.asLibraryDependency"))
        assertEquals(listOf("co.touchlab.crashkios:crashlytics:0.9.0", "com.squareup.okio:okio:3.9.0"), catalog.coordinates("bundles.tracking"))
        assertEquals(emptyList(), catalog.coordinates("no.such.alias"))
    }

    // ---- Binaries --------------------------------------------------------

    @Test
    fun binarySuffixesAndSharedObjectsAreReported() {
        assertEquals("shared library", FdroidScannerReplica.binaryKind("app/src/main/jniLibs/arm64-v8a/libfoo.so"))
        assertEquals("shared library", FdroidScannerReplica.binaryKind("vendor/libfoo.so.1.2"))
        assertEquals("'.aar' binary", FdroidScannerReplica.binaryKind("libs/thing.aar"))
        assertEquals("'.jar' binary", FdroidScannerReplica.binaryKind("libs/thing.jar"))
        assertEquals("'.apk' binary", FdroidScannerReplica.binaryKind("dist/app.apk"))
    }

    @Test
    fun scannerExemptNamesAndOrdinarySourcesAreNotReported() {
        assertNull(FdroidScannerReplica.binaryKind("gradle/wrapper/gradle-wrapper.jar"))
        assertNull(FdroidScannerReplica.binaryKind("gradlew"))
        assertNull(FdroidScannerReplica.binaryKind("gradlew.bat"))
        assertNull(FdroidScannerReplica.binaryKind("src/main/kotlin/Foo.kt"))
        assertNull(FdroidScannerReplica.binaryKind("art/logo.png"))
        assertNull(FdroidScannerReplica.binaryKind("docs/notes.solo"))
    }

    @Test
    fun sniffSelectionFollowsPythonSplitext() {
        assertTrue(FdroidScannerReplica.isSniffed(".gitignore"))
        assertTrue(FdroidScannerReplica.isSniffed("util/.gitignore"))
        assertTrue(FdroidScannerReplica.isSniffed(".gitmodules"))
        assertTrue(FdroidScannerReplica.isSniffed("LICENSE"))
        assertTrue(FdroidScannerReplica.isSniffed("models/for-tests-ggml-tiny.bin"))
        assertTrue(FdroidScannerReplica.isSniffed("tool.exe"))
        assertTrue(FdroidScannerReplica.isSniffed("a.out"))
        assertFalse(FdroidScannerReplica.isSniffed("foo."))
        assertFalse(FdroidScannerReplica.isSniffed(".config.yml"))
        assertFalse(FdroidScannerReplica.isSniffed("README.md"))
        assertFalse(FdroidScannerReplica.isSniffed("gradlew"))
        assertFalse(FdroidScannerReplica.isSniffed("libfoo.so"))
    }

    @Test
    fun byteSniffMatchesTheScannerHeuristic() {
        assertTrue(FdroidScannerReplica.isBinary(byteArrayOf(0x6C, 0x6D, 0x67, 0x67, 0x00, 0x00)))
        assertTrue(FdroidScannerReplica.isBinary("text then \u007F (DEL)".toByteArray()))
        assertFalse(FdroidScannerReplica.isBinary("plain text\n\twith tabs\r\n".toByteArray()))
        assertFalse(FdroidScannerReplica.isBinary("high bytes are text: éÿ".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(FdroidScannerReplica.isBinary(ByteArray(0)))
    }

    // ---- Other source checks --------------------------------------------

    @Test
    fun dependencyManifestWithoutLockfileIsReported() {
        val tracked = setOf("web/package.json", "web/src/index.js")
        assertTrue(FdroidScannerReplica.lacksLockfile("web/package.json", tracked))
    }

    @Test
    fun lockfileInTheSameOrAnAncestorDirectorySatisfiesTheCheck() {
        assertFalse(FdroidScannerReplica.lacksLockfile("web/package.json", setOf("web/package.json", "web/yarn.lock")))
        assertFalse(FdroidScannerReplica.lacksLockfile("web/app/package.json", setOf("web/app/package.json", "package-lock.json")))
        assertFalse(FdroidScannerReplica.lacksLockfile("rust/Cargo.toml", setOf("rust/Cargo.toml", "rust/Cargo.lock")))
        assertFalse(FdroidScannerReplica.lacksLockfile("web/index.js", setOf("web/index.js")))
    }

    @Test
    fun dexClassLoaderInJavaIsReported() {
        assertTrue(FdroidScannerReplica.usesDexClassLoader("src/Loader.java", listOf("import dalvik.system.DexClassLoader;")))
        assertFalse(FdroidScannerReplica.usesDexClassLoader("src/Loader.java", listOf("import java.io.File;")))
        assertFalse(FdroidScannerReplica.usesDexClassLoader("src/Loader.kt", listOf("import dalvik.system.DexClassLoader")))
    }
}
