package coredevices.coreapp.model

import coredevices.util.models.WhisperModelCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * Pins the optional-model resolve the voice activity detector uses: it
 * never downloads, an absent file reads as null, an intact file resolves
 * through the same load-time re-hash as a speech model, and a corrupt
 * file is quarantined and reads as null so the engine runs without the
 * detector instead of loading bad bytes or failing the dictation.
 */
class ResolveOptionalModelPathTest {

    private val root: File = Files.createTempDirectory("resolve-optional-test").toFile()
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val payload = ByteArray(2048) { (it % 199).toByte() }

    // The real detector entry's shape (id, file name, repository) with a
    // test payload's pin, so the on-disk layout matches production.
    private val model = WhisperModelCatalog.VAD_MODEL.copy(
        sha256 = sha256Hex(payload),
        sizeBytes = payload.size.toLong(),
    )

    private val installedFile: File get() = modelsDir.resolve(model.id).resolve(model.fileName)

    private fun installerRefusingNetwork() = ModelFileInstaller(
        httpClient = HttpClient(MockEngine { request ->
            error("the optional resolve must never download, got ${request.url}")
        }),
        modelsDir = modelsDir,
        readStallTimeout = Duration.INFINITE,
    )

    private fun seedInstalled(bytes: ByteArray) {
        installedFile.parentFile!!.mkdirs()
        installedFile.writeBytes(bytes)
    }

    @Test
    fun absentDetectorReadsAsNullWithoutNetwork() = runTest {
        val loadVerified = mutableMapOf<String, Boolean>()
        assertNull(WhisperModelProvider.resolveOptionalModelPath(installerRefusingNetwork(), loadVerified, model))
        assertFalse(loadVerified.containsKey(model.id))
    }

    @Test
    fun intactDetectorResolvesAndMemoizes() = runTest {
        seedInstalled(payload)
        val loadVerified = mutableMapOf<String, Boolean>()
        val path = WhisperModelProvider.resolveOptionalModelPath(installerRefusingNetwork(), loadVerified, model)
        assertEquals(installedFile.absolutePath, path)
        assertEquals(mapOf(model.id to true), loadVerified)
    }

    @Test
    fun corruptDetectorIsQuarantinedAndReadsAsNull() = runTest {
        seedInstalled(payload.copyOf().also { it[7] = (it[7] + 1).toByte() })
        val loadVerified = mutableMapOf<String, Boolean>()
        assertNull(WhisperModelProvider.resolveOptionalModelPath(installerRefusingNetwork(), loadVerified, model))
        assertFalse(installedFile.exists(), "the corrupt file must not stay in the installed slot")
        assertFalse(loadVerified.containsKey(model.id))
    }
}
