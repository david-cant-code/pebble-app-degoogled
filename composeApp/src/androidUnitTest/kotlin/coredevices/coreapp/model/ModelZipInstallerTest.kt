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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the extracted model install pipeline against synthetic zips and a
 * mock HTTP engine: the happy path (download, extract, single-root
 * promotion, temp cleanup), the bundled-asset path, and the failure
 * behavior the provider relies on (download failures leave an existing
 * model untouched, Zip-Slip entries refuse the install, a stalled transfer
 * fails instead of hanging). Lives in androidUnitTest because the fixtures
 * are built with java.util.zip.
 */
class ModelZipInstallerTest {

    private val root = Files.createTempDirectory("model-zip-test").toFile()
    private val cacheDir = root.resolve("cache").also { it.mkdirs() }
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private val model = "parakeet-tdt-0.6b-v3"
    private val targetDir: File get() = modelsDir.resolve(model)

    private val requestedUrls = mutableListOf<String>()

    private fun installer(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = ModelZipInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requestedUrls += request.url.toString()
            handler(request)
        }),
        cacheDir = cacheDir,
        modelsDir = modelsDir,
    )

    // --- Fixtures ---

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

    // --- Tests ---

    @Test
    fun downloadHappyPathInstallsModel() = runTest {
        installer(serving(modelZip())).install(model, "v2.0.1", copyBundledZip = null)
        assertEquals("cfg", targetDir.resolve("config.txt").readText())
        assertEquals(
            listOf("https://huggingface.co/Cactus-Compute/$model/resolve/v2.0.1/${model.lowercase()}-cq4.zip"),
            requestedUrls,
        )
        assertTrue(cacheFilesLeft().isEmpty(), "temp zip should be deleted after install")
    }

    @Test
    fun singleRootDirectoryIsPromoted() = runTest {
        installer(serving(modelZip(prefix = "$model/"))).install(model, "v2.0.1", copyBundledZip = null)
        assertTrue(targetDir.resolve("config.txt").exists(), "contents should be promoted to the model dir")
        assertFalse(targetDir.resolve(model).exists(), "the wrapping root dir should be gone")
    }

    @Test
    fun existingModelIsReplaced() = runTest {
        seedOldModel()
        installer(serving(modelZip())).install(model, "v2.0.1", copyBundledZip = null)
        assertEquals("cfg", targetDir.resolve("config.txt").readText())
        assertFalse(targetDir.resolve("old.weights").exists(), "stale files must not survive a reinstall")
    }

    @Test
    fun bundledAssetInstallsWithoutTouchingTheNetwork() = runTest {
        val zipBytes = modelZip()
        installer(serving(zipBytes)).install(model, "v2.0.1", copyBundledZip = { dest ->
            dest.writeBytes(zipBytes)
        })
        assertTrue(targetDir.resolve("config.txt").exists())
        assertEquals(emptyList(), requestedUrls)
        assertTrue(cacheFilesLeft().isEmpty())
    }

    @Test
    fun httpErrorFailsAndLeavesExistingModelIntact() = runTest {
        seedOldModel()
        val e = assertFailsWith<Exception> {
            installer { respond(ByteReadChannel("gone".encodeToByteArray()), HttpStatusCode.NotFound) }
                .install(model, "v2.0.1", copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("HTTP 404"), "unexpected failure: $e")
        assertEquals("old", targetDir.resolve("config.txt").readText())
        assertTrue(cacheFilesLeft().isEmpty())
    }

    @Test
    fun parentTraversalEntryIsRefused() = runTest {
        val evil = zip(mapOf("../evil.txt" to "evil".encodeToByteArray()))
        assertFailsWith<SecurityException> {
            installer(serving(evil)).install(model, "v2.0.1", copyBundledZip = null)
        }
        assertFalse(modelsDir.resolve("evil.txt").exists(), "entry must not escape the target dir")
        assertFalse(targetDir.exists(), "a refused install should not leave a partial model dir")
        assertTrue(cacheFilesLeft().isEmpty())
    }

    @Test
    fun stalledDownloadFailsInsteadOfHangingForever() = runTest {
        // A channel that serves a few bytes and then goes quiet, like a dead
        // socket the peer never closes.
        val stalled = ByteChannel(autoFlush = true)
        stalled.writeFully(ByteArray(10))
        val e = assertFailsWith<Exception> {
            installer { respond(stalled, HttpStatusCode.OK) }.install(model, "v2.0.1", copyBundledZip = null)
        }
        assertTrue(e.message.orEmpty().contains("stalled"), "unexpected failure: $e")
        assertTrue(cacheFilesLeft().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationMidDownloadLeavesExistingModelIntact() = runTest {
        seedOldModel()
        val open = ByteChannel(autoFlush = true)
        open.writeFully(ByteArray(1024))
        val job = launch {
            installer { respond(open, HttpStatusCode.OK) }.install(model, "v2.0.1", copyBundledZip = null)
        }
        // Let the download start and suspend on the never-closing channel,
        // then cancel it the way an aborted STT resolve would.
        runCurrent()
        job.cancelAndJoin()
        assertEquals("old", targetDir.resolve("config.txt").readText())
        assertTrue(cacheFilesLeft().isEmpty(), "a cancelled download should not leave a temp zip behind")
    }
}
