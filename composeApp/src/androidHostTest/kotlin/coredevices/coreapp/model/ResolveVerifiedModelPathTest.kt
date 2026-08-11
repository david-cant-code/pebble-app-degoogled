package coredevices.coreapp.model

import coredevices.util.models.WhisperModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Pins the load-time verification orchestration
 * ([WhisperModelProvider.resolveVerifiedModelPath]) against a real
 * [ModelFileInstaller] over a mock HTTP engine: it is the only place the
 * third integrity layer (re-hash before first use per process) is wired
 * in, so a regression here silently deletes that layer for every load
 * while the well-tested installer leaves keep passing. The unreachable
 * remainder (a fresh reinstall that fails its own verify) is not
 * simulated: the installer's download-time digest gate throws before an
 * unverified file can ever be promoted, which is the layering working.
 */
class ResolveVerifiedModelPathTest {

    private val root: File = Files.createTempDirectory("resolve-verified-test").toFile()
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private val model = WhisperModel(
        id = "whisper-base-en",
        displayName = "Test model",
        fileName = "ggml-test.bin",
        sha256 = sha256Hex(payload),
        sizeBytes = payload.size.toLong(),
        minRamBytes = 1,
        multilingual = false,
    )

    private val installedFile: File get() = modelsDir.resolve(model.id).resolve(model.fileName)

    private val requestedUrls = mutableListOf<String>()

    private fun installerServing(bytes: ByteArray?) = ModelFileInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requestedUrls += request.url.toString()
            if (bytes == null) {
                error("this test expects no network traffic, got ${request.url}")
            }
            respond(ByteReadChannel(bytes), HttpStatusCode.OK)
        }),
        modelsDir = modelsDir,
        // Virtual runTest clock vs the MockEngine's real-dispatcher body
        // writer; a finite stall timeout would race it spuriously.
        readStallTimeout = Duration.INFINITE,
    )

    private fun seedInstalled(bytes: ByteArray) {
        installedFile.parentFile!!.mkdirs()
        installedFile.writeBytes(bytes)
    }

    @Test
    fun intactInstallResolvesWithoutNetworkAndMemoizes() = runTest {
        seedInstalled(payload)
        val loadVerified = mutableMapOf<String, Boolean>()
        val path = WhisperModelProvider.resolveVerifiedModelPath(
            installerServing(null), loadVerified, model,
        )
        assertEquals(installedFile.absolutePath, path)
        assertEquals(mapOf(model.id to true), loadVerified, "the re-hash result must be memoized")
    }

    @Test
    fun missingModelInstallsVerifiesAndResolves() = runTest {
        val loadVerified = mutableMapOf<String, Boolean>()
        val path = WhisperModelProvider.resolveVerifiedModelPath(
            installerServing(payload), loadVerified, model,
        )
        assertEquals(installedFile.absolutePath, path)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(1, requestedUrls.size, "a missing model needs exactly one download")
        assertEquals(mapOf(model.id to true), loadVerified)
    }

    @Test
    fun corruptInstallIsQuarantinedAndReinstalledOnce() = runTest {
        // Right size, wrong bytes: only the load-time re-hash can catch it.
        seedInstalled(payload.copyOf().also { it[10] = (it[10] + 1).toByte() })
        val loadVerified = mutableMapOf<String, Boolean>()
        val path = WhisperModelProvider.resolveVerifiedModelPath(
            installerServing(payload), loadVerified, model,
        )
        assertEquals(installedFile.absolutePath, path)
        assertContentEquals(payload, installedFile.readBytes(), "the reinstall must heal the file")
        assertEquals(1, requestedUrls.size, "exactly one fresh reinstall, not a retry loop")
        assertEquals(mapOf(model.id to true), loadVerified)
    }

    @Test
    fun memoizedVerificationIsNotRepeated() = runTest {
        // Once-per-process is the documented contract: within a process
        // the file only changes through the provider, and re-hashing
        // hundreds of MB per transcription is not affordable. A corrupt
        // file behind a memoized entry is therefore NOT re-detected here;
        // this pins the memo semantics, not a verification bypass.
        seedInstalled(payload.copyOf().also { it[10] = (it[10] + 1).toByte() })
        val loadVerified = mutableMapOf(model.id to true)
        val path = WhisperModelProvider.resolveVerifiedModelPath(
            installerServing(null), loadVerified, model,
        )
        assertEquals(installedFile.absolutePath, path)
        assertEquals(mapOf(model.id to true), loadVerified)
    }

    @Test
    fun staleMemoDoesNotSurviveAFreshInstall() = runTest {
        // A stale true for a model that is no longer installed must not
        // let the fresh bytes skip their first re-hash; the install path
        // drops the memo entry before verification re-establishes it.
        val loadVerified = mutableMapOf(model.id to true)
        val path = WhisperModelProvider.resolveVerifiedModelPath(
            installerServing(payload), loadVerified, model,
        )
        assertEquals(installedFile.absolutePath, path)
        assertTrue(installedFile.isFile)
        assertEquals(mapOf(model.id to true), loadVerified)
    }
}
