package coredevices.coreapp

import coredevices.coreapp.FdroidScannerReplica.VersionCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F-Droid's source scanner, applied to this repository's tracked tree on
 * every CI run.
 *
 * The fork targets inclusion in F-Droid's main repository, and most of the
 * ways that can regress arrive silently with an upstream sync or a submodule
 * bump: a dependency line naming a Google artifact, a publishing block naming
 * a private maven host, a checked-in native library or archive. F-Droid only
 * reports these at submission or on its next build of a tag, long after the
 * merge, so this class fails the merge itself instead. The checks are
 * [FdroidScannerReplica]'s; [FdroidScannerReplicaTest] proves each of them
 * fires on a known-bad sample, so a green run here means the tree is clean
 * rather than that a matcher went quiet.
 *
 * Three independent guards stand between an upstream sync and a policy
 * regression, and this class is the source-text one:
 * - the classpath sentinels ([AppClasspathSentinelTest] and the library
 *   copies) prove the shipped runtime graph is clean;
 * - the built APK is inspected directly (fork rule: verify against the
 *   artifact, not the tree);
 * - this class proves the tracked *tree* passes the scanner as F-Droid runs
 *   it, which the other two cannot see: an unplugged module's build file, an
 *   iOS-only dependency line, or a publishing block never reaches the
 *   Android classpath yet still fails the scan.
 *
 * Scope is what F-Droid scans: the tracked tree of the superproject plus the
 * checked-out whisper.cpp submodule (the buildserver clones it, and
 * scanner.py walks it like any other directory), minus the paths the F-Droid
 * build recipe removes before its scan, listed in [recipeRemovedPaths]. That
 * list is the tree's half of a contract with the out-of-tree recipe, and
 * DESIGN_NOTES.md (F-Droid section) records the whole contract.
 */
class FdroidGuardrailsTest {

    /**
     * Paths (relative to the checkout root, directories with a trailing
     * slash) that the F-Droid build recipe deletes with its `rm:` field
     * before building and scanning, so the scanner never sees them. Kept
     * here so a submodule bump that adds a scanner hit outside them fails
     * CI, and so the recipe can be written from the tree. Every entry must
     * still exist in the tree ([recipeRemovedPathsStillExist]), otherwise
     * the list has rotted away from the recipe. `rm:` rather than
     * `scandelete:` on purpose: the scanner counts a scandelete entry that
     * removed nothing as an error, `rm:` tolerates a missing path.
     */
    private val recipeRemovedPaths = listOf(
        // whisper.cpp ships language bindings with their own gradle builds and
        // a lockfile-less package.json, example apps with a maven URL off the
        // allowlist, binary test-fixture models, sample audio, and its own
        // test suite; none of it takes part in the fork's build.
        "whisper-native/src/main/cpp/whisper.cpp/bindings/",
        "whisper-native/src/main/cpp/whisper.cpp/examples/",
        "whisper-native/src/main/cpp/whisper.cpp/models/",
        "whisper-native/src/main/cpp/whisper.cpp/samples/",
        "whisper-native/src/main/cpp/whisper.cpp/tests/",
    )

    /** The tracked tree as the scanner sees it after the recipe's removals. */
    private val scannedFiles: List<String> by lazy {
        TrackedTree.files.filterNot { path -> recipeRemovedPaths.any { path.startsWith(it) } }
    }

    private fun scannedGradleFiles(): List<String> = scannedFiles.filter(FdroidScannerReplica::isGradleFile)

    private fun read(path: String): File = TrackedTree.file(path)

    @Test
    fun recipeRemovedPathsStillExist() {
        val stale = recipeRemovedPaths.filter { prefix -> TrackedTree.files.none { it.startsWith(prefix) } }
        assertTrue(
            stale.isEmpty(),
            "recipeRemovedPaths names paths no longer in the tree; update the list and the F-Droid recipe together:\n" +
                stale.joinToString("\n"),
        )
    }

    @Test
    fun everyMavenRepositoryUrlIsOnTheFdroidAllowlist() {
        val problems = scannedGradleFiles().flatMap { path ->
            FdroidScannerReplica.unknownMavenRepos(read(path).readText()).map { "$path: unknown maven repo '$it'" }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid rejects builds that declare maven repositories off its allowlist:\n" + problems.joinToString("\n"),
        )
    }

    /**
     * Catalogs are resolved per settings root, as scanner.py does: the
     * catalog for a gradle file is the default catalog of the nearest
     * ancestor directory holding a settings file. A settings file declaring
     * catalogs the replica cannot read fails outright (see
     * [FdroidScannerReplica.declaresCatalogsTheReplicaCannotRead]).
     */
    @Test
    fun noDependencyLineNamesAnFdroidUsualSuspect() {
        val settingsRoots = scannedFiles
            .filter { it.substringAfterLast('/') in setOf("settings.gradle", "settings.gradle.kts") }
            .associate { it.substringBeforeLast('/', "") to it }
        val problems = mutableListOf<String>()
        settingsRoots.values.forEach { settings ->
            if (FdroidScannerReplica.declaresCatalogsTheReplicaCannotRead(read(settings).readText())) {
                problems += "$settings declares version catalogs this replica cannot read; extend FdroidScannerReplica"
            }
        }
        val catalogs = settingsRoots.keys.associateWith { root ->
            val catalog = if (root.isEmpty()) "gradle/libs.versions.toml" else "$root/gradle/libs.versions.toml"
            if (catalog in scannedFiles) VersionCatalog.parse(read(catalog).readLines()) else VersionCatalog.EMPTY
        }
        for (path in scannedGradleFiles()) {
            val root = catalogs.keys
                .filter { it.isEmpty() || path.startsWith("$it/") }
                .maxByOrNull { it.length }
            val catalog = root?.let { catalogs[it] } ?: VersionCatalog.EMPTY
            problems += FdroidScannerReplica.usualSuspects(read(path).readLines(), catalog).map { "$path, $it" }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner fails the build on these dependency lines:\n" + problems.joinToString("\n"),
        )
    }

    /** The parser must actually be resolving the real catalog, not degrading to a map that answers nothing. */
    @Test
    fun theRealCatalogResolvesAKnownAccessor() {
        val catalog = VersionCatalog.parse(read("gradle/libs.versions.toml").readLines())
        val coordinates = catalog.coordinates("androidx.core.ktx")
        assertTrue(
            coordinates.singleOrNull()?.startsWith("androidx.core:core-ktx:") == true,
            "libs.androidx.core.ktx resolved to $coordinates; the catalog reader is no longer reading gradle/libs.versions.toml",
        )
    }

    @Test
    fun noBinaryIsTrackedInTheTree() {
        val problems = scannedFiles.mapNotNull { path -> FdroidScannerReplica.binaryKind(path)?.let { "$path: $it" } }
        assertTrue(
            problems.isEmpty(),
            "F-Droid rejects checked-in binaries; the build must produce them from source:\n" + problems.joinToString("\n"),
        )
    }

    @Test
    fun noSniffedTrackedFileIsBinary() {
        val problems = scannedFiles.filter { path ->
            FdroidScannerReplica.isSniffed(path) && read(path).let { file ->
                file.isFile && FdroidScannerReplica.isBinary(file.inputStream().use { it.readNBytes(1024) })
            }
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner sniffs these files as binaries:\n" + problems.joinToString("\n"),
        )
    }

    @Test
    fun everyDependencyManifestHasALockfile() {
        val tracked = scannedFiles.toSet()
        val problems = scannedFiles.filter { FdroidScannerReplica.lacksLockfile(it, tracked) }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner fails on dependency files without a lockfile:\n" + problems.joinToString("\n"),
        )
    }

    @Test
    fun noJavaSourceUsesDexClassLoader() {
        val problems = scannedFiles.filter { path ->
            path.endsWith(".java") && FdroidScannerReplica.usesDexClassLoader(path, read(path).readLines())
        }
        assertTrue(
            problems.isEmpty(),
            "F-Droid's scanner fails on Java sources that load code at runtime:\n" + problems.joinToString("\n"),
        )
    }
}
