package coredevices.coreapp.model

import coredevices.util.models.WhisperModel
import coredevices.util.models.WhisperModelCatalog
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Pins the install orchestration above [WhisperModelProvider.resolveVerifiedModelPath]:
 * a speech model download brings the detector along, a detector failure
 * leaves the speech model resolved, the init path touches nothing on the
 * network, the detector resolves by its own id, and the id lookup admits
 * the detector although it is not a pickable model.
 */
class ResolveModelWithDetectorTest {

    private val root: File = Files.createTempDirectory("resolve-with-detector-test").toFile()
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val speechPayload = ByteArray(4096) { (it % 251).toByte() }
    private val detectorPayload = ByteArray(1024) { (it % 13).toByte() }

    private val speech = WhisperModel(
        id = "whisper-base-en",
        displayName = "Test model",
        fileName = "ggml-test.bin",
        sha256 = sha256Hex(speechPayload),
        sizeBytes = speechPayload.size.toLong(),
        minRamBytes = 1,
        multilingual = false,
    )
    private val detector = WhisperModel(
        id = "vad-silero",
        displayName = "Test detector",
        fileName = "ggml-silero-test.bin",
        sha256 = sha256Hex(detectorPayload),
        sizeBytes = detectorPayload.size.toLong(),
        minRamBytes = 1,
        multilingual = true,
    )

    private val requested = mutableListOf<String>()

    /** Serves each model's file by name; a null payload for a name makes that download fail. */
    private fun installer(serving: Map<String, ByteArray?>) = ModelFileInstaller(
        httpClient = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val name = serving.keys.firstOrNull { url.endsWith(it) } ?: error("unexpected request $url")
            val bytes = serving[name] ?: error("simulated download failure for $name")
            respond(ByteReadChannel(bytes), HttpStatusCode.OK)
        }),
        modelsDir = modelsDir,
        readStallTimeout = Duration.INFINITE,
    )

    @Test
    fun aSpeechModelDownloadBringsTheDetectorAlong() = runTest {
        val installer = installer(mapOf(speech.fileName to speechPayload, detector.fileName to detectorPayload))
        val path = WhisperModelProvider.resolveModelWithDetector(installer, mutableMapOf(), speech, allowReinstall = true, detector)
        assertEquals(installer.installedFile(speech).absolutePath, path)
        assertTrue(installer.isInstalled(detector))
        assertEquals(2, requested.size)
    }

    @Test
    fun aDetectorFailureLeavesTheSpeechModelResolved() = runTest {
        val installer = installer(mapOf(speech.fileName to speechPayload, detector.fileName to null))
        val path = WhisperModelProvider.resolveModelWithDetector(installer, mutableMapOf(), speech, allowReinstall = true, detector)
        assertEquals(installer.installedFile(speech).absolutePath, path)
        assertFalse(installer.isInstalled(detector))
    }

    @Test
    fun theInitPathNeverTouchesTheNetwork() = runTest {
        installer(mapOf(speech.fileName to speechPayload)).install(speech)
        requested.clear()
        val silent = installer(emptyMap())
        val path = WhisperModelProvider.resolveModelWithDetector(silent, mutableMapOf(), speech, allowReinstall = false, detector)
        assertEquals(silent.installedFile(speech).absolutePath, path)
        assertTrue(requested.isEmpty())
        assertFalse(silent.isInstalled(detector))
    }

    @Test
    fun theDetectorResolvesByItsOwnIdWithoutRecursing() = runTest {
        val installer = installer(mapOf(detector.fileName to detectorPayload))
        val path = WhisperModelProvider.resolveModelWithDetector(installer, mutableMapOf(), detector, allowReinstall = true, detector)
        assertEquals(installer.installedFile(detector).absolutePath, path)
        assertEquals(1, requested.size)
    }

    @Test
    fun theDownloadLookupAdmitsTheDetectorAndNothingElseOutsideTheCatalog() {
        assertSame(WhisperModelCatalog.VAD_MODEL, WhisperModelProvider.catalogModelForDownload(WhisperModelCatalog.VAD_MODEL.id))
        assertSame(WhisperModelCatalog.byId("whisper-base-en"), WhisperModelProvider.catalogModelForDownload("whisper-base-en"))
        assertNull(WhisperModelProvider.catalogModelForDownload("not-a-model"))
    }
}
