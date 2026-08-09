package coredevices.coreapp.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the verified model install pipeline against synthetic zips and a
 * mock HTTP engine: the happy path (commit-pinned URL, digest gate, staged
 * swap, marker content, temp cleanup), the bundled-asset path through the
 * same gate, and every refusal branch (digest and size mismatches,
 * mid-stream oversize abort, Zip-Slip in its three shapes, entry-count and
 * inflation caps, stalls, cancellation). The invariant asserted
 * throughout: no failure mode ever costs the existing model, and nothing
 * unverified is left behind in staging or cache. Lives in androidHostTest
 * because the fixtures are built with java.util.zip.
 */
class ModelZipInstallerTest {

    private val root = Files.createTempDirectory("model-zip-test").toFile()
    private val cacheDir = root.resolve("cache").also { it.mkdirs() }
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private val model = "parakeet-tdt-0.6b-v3"
    private val targetDir: File get() = modelsDir.resolve(model)
    private val stagingDir: File get() = modelsDir.resolve(ModelZipInstaller.STAGING_DIR).resolve(model)
    private val oldAsideDir: File get() =
        modelsDir.resolve(ModelZipInstaller.STAGING_DIR).resolve("$model${ModelZipInstaller.OLD_ASIDE_SUFFIX}")

    private val requestedUrls = mutableListOf<String>()

    private fun installer(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = ModelZipInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requestedUrls += request.url.toString()
            handler(request)
        }),
        // Infinite: the MockEngine body writer runs on a real dispatcher while
        // runTest's clock is virtual, so any multi-chunk read would lose the
        // race against the 30s production stall timeout spuriously. The one
        // test of the stall branch itself builds its own installer with a
        // finite timeout instead of using this helper.
        readStallTimeout = Duration.INFINITE,
        cacheDir = cacheDir,
        modelsDir = modelsDir,
    )

    // --- Fixtures ---

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val testCommit = "0123456789abcdef0123456789abcdef01234567"

    /** A pin that genuinely matches [bytes], as a correct pin table would. */
    private fun pinFor(bytes: ByteArray, sizeBytes: Long = bytes.size.toLong()) = ModelPin(
        hfRepo = model,
        commitSha = testCommit,
        zipSha256Hex = sha256Hex(bytes),
        zipSizeBytes = sizeBytes,
    )

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Minimal shape of a real model archive; [prefix] nests it under a root dir. */
    private fun modelZip(prefix: String = ""): ByteArray = zip(
        mapOf(
            "${prefix}config.txt" to "cfg".encodeToByteArray(),
            "${prefix}vocab.txt" to "vocab".encodeToByteArray(),
            "${prefix}model.weights" to ByteArray(64) { it.toByte() },
        ),
    )

    private fun serving(bytes: ByteArray): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(ByteReadChannel(bytes), HttpStatusCode.OK) }

    private fun seedOldModel() {
        targetDir.mkdirs()
        targetDir.resolve("config.txt").writeText("old")
        targetDir.resolve("old.weights").writeText("old-weights")
    }

    private fun cacheFilesLeft(): List<String> = cacheDir.listFiles()?.map { it.name } ?: emptyList()

    private fun assertNothingLeftBehind() {
        assertTrue(cacheFilesLeft().isEmpty(), "temp zip should be deleted")
        assertFalse(stagingDir.exists(), "staging should be cleaned up")
        assertFalse(oldAsideDir.exists(), "no parked old model should remain")
    }

    private fun assertOldModelIntact() {
        assertEquals("old", targetDir.resolve("config.txt").readText(), "existing model must survive a failed install")
        assertTrue(targetDir.resolve("old.weights").exists())
    }

    // --- Happy paths ---

    @Test
    fun downloadHappyPathInstallsVerifiedModel() = runTest {
        val bytes = modelZip()
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertEquals("cfg", targetDir.resolve("config.txt").readText())
        assertEquals(
            listOf("https://huggingface.co/Cactus-Compute/$model/resolve/$testCommit/${model.lowercase()}-cq4.zip"),
            requestedUrls,
        )
        assertEquals(
            sha256Hex(bytes),
            targetDir.resolve(ModelZipInstaller.VERSION_MARKER).readText(),
            "marker must carry the pinned digest so a pin change forces reinstall",
        )
        assertNothingLeftBehind()
    }

    @Test
    fun singleRootDirectoryIsPromoted() = runTest {
        val bytes = modelZip(prefix = "$model/")
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertTrue(targetDir.resolve("config.txt").exists(), "contents should be promoted to the model dir")
        assertFalse(targetDir.resolve(model).exists(), "the wrapping root dir should be gone")
    }

    @Test
    fun existingModelIsReplaced() = runTest {
        seedOldModel()
        val bytes = modelZip()
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertEquals("cfg", targetDir.resolve("config.txt").readText())
        assertFalse(targetDir.resolve("old.weights").exists(), "stale files must not survive a reinstall")
    }

    @Test
    fun bundledAssetInstallsWithoutTouchingTheNetwork() = runTest {
        val bytes = modelZip()
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = { dest ->
            dest.writeBytes(bytes)
        })
        assertTrue(targetDir.resolve("config.txt").exists())
        assertEquals(sha256Hex(bytes), targetDir.resolve(ModelZipInstaller.VERSION_MARKER).readText())
        assertEquals(emptyList(), requestedUrls)
        assertNothingLeftBehind()
    }

    @Test
    fun staleStagingIsSweptBeforeInstall() = runTest {
        stagingDir.mkdirs()
        stagingDir.resolve("junk.txt").writeText("left by a crashed install")
        val bytes = modelZip()
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertFalse(targetDir.resolve("junk.txt").exists(), "unverified leftovers must not ride into the model dir")
        assertNothingLeftBehind()
    }

    // --- Integrity gate ---

    @Test
    fun digestMismatchRefusesInstall() = runTest {
        seedOldModel()
        val served = modelZip()
        // Same length, different bytes: only the digest can catch it.
        val expected = served.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertFailsWith<SecurityException> {
            installer(serving(served)).install(model, pinFor(expected), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("failed verification"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun shortBodyFailsTheExactSizeCheck() = runTest {
        seedOldModel()
        val bytes = modelZip()
        // Pin expects more bytes than the server delivers; digest of the
        // truncated body may or may not differ, size alone must refuse.
        val e = assertFailsWith<SecurityException> {
            installer(serving(bytes)).install(model, pinFor(bytes, sizeBytes = bytes.size + 5L), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("failed verification"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun oversizeStreamIsAbortedMidDownload() = runTest {
        seedOldModel()
        val bytes = modelZip()
        // Server keeps sending past the pinned size: the abort must happen
        // mid-stream, before the payload is fully consumed.
        val oversize = bytes + ByteArray(4096)
        val e = assertFailsWith<SecurityException> {
            installer(serving(oversize)).install(model, pinFor(bytes), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("exceeded the pinned"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun bundledAssetGoesThroughTheSameDigestGate() = runTest {
        seedOldModel()
        val genuine = modelZip()
        val tampered = genuine.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertFailsWith<SecurityException> {
            installer(serving(genuine)).install(model, pinFor(genuine), copyBundledZip = { dest ->
                dest.writeBytes(tampered)
            })
        }
        assertTrue(e.message.orEmpty().contains("failed verification"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun archiveWithoutConfigRefusesToReplaceAWorkingModel() = runTest {
        seedOldModel()
        val bytes = zip(mapOf("readme.txt" to "not a model".encodeToByteArray()))
        val e = assertFailsWith<SecurityException> {
            installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("config.txt"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    // --- Extraction confinement ---

    @Test
    fun parentTraversalEntryIsRefused() = runTest {
        val evil = zip(mapOf("../evil.txt" to "evil".encodeToByteArray()))
        assertFailsWith<SecurityException> {
            installer(serving(evil)).install(model, pinFor(evil), copyBundledZip = null)
        }
        assertFalse(modelsDir.resolve(ModelZipInstaller.STAGING_DIR).resolve("evil.txt").exists(), "entry must not escape staging")
        assertFalse(targetDir.exists(), "a refused install should not leave a model dir")
        assertNothingLeftBehind()
    }

    @Test
    fun siblingPrefixEntryIsRefused() = runTest {
        // "../<model>-evil/x" canonicalizes to a sibling whose name starts
        // with the staging dir's: a bare prefix comparison would accept it.
        val evil = zip(mapOf("../$model-evil/x.txt" to "evil".encodeToByteArray()))
        assertFailsWith<SecurityException> {
            installer(serving(evil)).install(model, pinFor(evil), copyBundledZip = null)
        }
        assertFalse(modelsDir.resolve(ModelZipInstaller.STAGING_DIR).resolve("$model-evil").exists(), "sibling dir must not be created")
        assertNothingLeftBehind()
    }

    @Test
    fun absolutePathEntryStaysConfined() = runTest {
        // java.io.File resolves an absolute child under the parent, so the
        // entry lands inside the model dir; this pins that containment.
        val bytes = zip(
            mapOf(
                "config.txt" to "cfg".encodeToByteArray(),
                "/evil.txt" to "evil".encodeToByteArray(),
            ),
        )
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertTrue(targetDir.resolve("evil.txt").exists(), "absolute entry should be confined to the model dir")
        assertFalse(modelsDir.resolve("evil.txt").exists())
    }

    @Test
    fun entryCountCapRefusesTheArchive() = runTest {
        val many = zip(
            (0..ModelZipInstaller.MAX_ENTRIES).associate { "f$it.txt" to ByteArray(0) },
        )
        val e = assertFailsWith<SecurityException> {
            installer(serving(many)).install(model, pinFor(many), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("entries"), "unexpected failure: $e")
        assertNothingLeftBehind()
    }

    @Test
    fun inflationCapRefusesAZipBomb() = runTest {
        // A megabyte of zeros compresses to ~1 KB, so the pinned zip size is
        // tiny while the inflated output blows past the cap factor.
        val bomb = zip(mapOf("config.txt" to ByteArray(1024 * 1024)))
        val e = assertFailsWith<SecurityException> {
            installer(serving(bomb)).install(model, pinFor(bomb), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("inflates"), "unexpected failure: $e")
        assertNothingLeftBehind()
    }

    // --- Transport failure behavior ---

    @Test
    fun httpErrorFailsAndLeavesExistingModelIntact() = runTest {
        seedOldModel()
        val bytes = modelZip()
        val e = assertFailsWith<Exception> {
            installer { respond(ByteReadChannel("gone".encodeToByteArray()), HttpStatusCode.NotFound) }
                .install(model, pinFor(bytes), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("HTTP 404"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun stalledDownloadFailsInsteadOfHangingForever() = runTest {
        // A channel that serves a few bytes and then goes quiet, like a dead
        // socket the peer never closes.
        val stalled = ByteChannel(autoFlush = true)
        stalled.writeFully(ByteArray(10))
        // Built without the installer() helper: this test verifies the stall
        // branch itself, so it needs a finite timeout where every other test
        // injects INFINITE to opt out (an infinite timeout now bypasses the
        // timer entirely, so with the helper this read would suspend forever
        // and trip runTest's watchdog). The finite timer runs on runTest's
        // virtual clock, which fast-forwards to it as soon as the quiet
        // channel leaves the dispatcher idle: deterministic, and the 30
        // virtual seconds cost no real time.
        val stallInstaller = ModelZipInstaller(
            httpClient = HttpClient(MockEngine { respond(stalled, HttpStatusCode.OK) }),
            readStallTimeout = 30.seconds,
            cacheDir = cacheDir,
            modelsDir = modelsDir,
        )
        val e = assertFailsWith<Exception> {
            stallInstaller.install(model, pinFor(modelZip()), copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("stalled"), "unexpected failure: $e")
        assertNothingLeftBehind()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationMidDownloadLeavesExistingModelIntact() = runTest {
        seedOldModel()
        val open = ByteChannel(autoFlush = true)
        open.writeFully(ByteArray(1024))
        val job = launch {
            installer { respond(open, HttpStatusCode.OK) }.install(model, pinFor(modelZip(), sizeBytes = 1 shl 20), copyBundledZip = null)
        }
        // Let the download start and suspend on the never-closing channel,
        // then cancel it the way an aborted STT resolve would.
        runCurrent()
        job.cancelAndJoin()
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun failureSurfacingDuringCancellationStillCleansUp() = runTest {
        val job = launch {
            installer(serving(modelZip())).install(model, pinFor(modelZip()), copyBundledZip = { dest ->
                // Stand-in for a transport teardown mid-pipeline: staging
                // already holds partial output when a plain IOException
                // surfaces on an already-cancelled coroutine (the
                // ensureActive re-check rethrows it as cancellation).
                stagingDir.mkdirs()
                stagingDir.resolve("partial.weights").writeText("partial")
                dest.writeBytes(ByteArray(3))
                currentCoroutineContext().cancel()
                throw IOException("stream torn down during cancellation")
            })
        }
        job.join()
        assertNothingLeftBehind()
    }

    // --- Swap robustness ---

    @Test
    fun verificationRunsBeforeAnyExtraction() = runTest {
        seedOldModel()
        // Non-zip garbage whose digest mismatches its pin: if extraction
        // ever ran ahead of the digest gate, this would surface as a
        // ZipException from the garbage instead of the verification
        // refusal.
        val garbage = ByteArray(512) { 0x41 }
        val wrongPin = pinFor(garbage).copy(zipSha256Hex = "ee".repeat(32))
        val e = assertFailsWith<SecurityException> {
            installer(serving(garbage)).install(model, wrongPin, copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("failed verification"), "unexpected failure: $e")
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun aParkedInstallFromACrashedSwapIsRestored() = runTest {
        // The state a process death between the two swap renames leaves:
        // no live model dir, the old install parked aside.
        oldAsideDir.mkdirs()
        oldAsideDir.resolve("config.txt").writeText("old")
        oldAsideDir.resolve("old.weights").writeText("old-weights")
        // Even an install attempt that itself fails must first restore it.
        assertFailsWith<Exception> {
            installer { respond(ByteReadChannel("gone".encodeToByteArray()), HttpStatusCode.NotFound) }
                .install(model, pinFor(modelZip()), copyBundledZip = null)
        }
        assertOldModelIntact()
        assertNothingLeftBehind()
    }

    @Test
    fun aStaleParkedCopyNextToALiveInstallIsSwept() = runTest {
        seedOldModel()
        oldAsideDir.mkdirs()
        oldAsideDir.resolve("junk.weights").writeText("stale")
        val bytes = modelZip()
        installer(serving(bytes)).install(model, pinFor(bytes), copyBundledZip = null)
        assertEquals("cfg", targetDir.resolve("config.txt").readText())
        assertNothingLeftBehind()
    }

    // --- On-disk contract ---

    @Test
    fun onDiskNamesAreAStableContract() {
        // Existing installs on user devices carry these exact names; a
        // rename must be a conscious migration, not a refactor side effect.
        assertEquals("config.txt", ModelZipInstaller.CONFIG_FILE)
        assertEquals(".cactus_version", ModelZipInstaller.VERSION_MARKER)
        assertEquals(".staging", ModelZipInstaller.STAGING_DIR)
        assertEquals(".old", ModelZipInstaller.OLD_ASIDE_SUFFIX)
    }
}
