package coredevices.coreapp.model

import coredevices.util.models.WhisperModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the verified single-file model install pipeline against a mock
 * HTTP engine: the happy path (commit-pinned URL, digest gate, atomic
 * promotion), the resume protocol (exact-206 continuation, clean restart
 * on anything else), the fail-closed boundary (complete-but-wrong bytes
 * delete the partial; stopped-early keeps it), and the load-time
 * re-verification with quarantine.
 */
class ModelFileInstallerTest {

    private val root = Files.createTempDirectory("model-file-test").toFile()
    private val modelsDir = root.resolve("models").also { it.mkdirs() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Deterministic non-trivial payload; small enough for fast tests,
    // large enough to span several read-buffer iterations when chunked.
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private fun modelFor(bytes: ByteArray, sizeBytes: Long = bytes.size.toLong()) = WhisperModel(
        id = "whisper-base-en",
        displayName = "Test model",
        fileName = "ggml-test.bin",
        sha256 = sha256Hex(bytes),
        sizeBytes = sizeBytes,
        minRamBytes = 1,
        multilingual = false,
    )

    private val model = modelFor(payload)

    private val installedFile: File get() = modelsDir.resolve(model.id).resolve(model.fileName)
    private val partialFile: File get() =
        modelsDir.resolve(ModelFileInstaller.STAGING_DIR).resolve("${model.id}${ModelFileInstaller.PARTIAL_SUFFIX}")
    private val quarantineFile: File get() =
        modelsDir.resolve(ModelFileInstaller.STAGING_DIR).resolve("${model.id}${ModelFileInstaller.QUARANTINE_SUFFIX}")

    private val requestedRanges = mutableListOf<String?>()
    private val requestedUrls = mutableListOf<String>()

    private fun installer(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = ModelFileInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requestedUrls += request.url.toString()
            requestedRanges += request.headers[HttpHeaders.Range]
            handler(request)
        }),
        modelsDir = modelsDir,
        // Infinite: the MockEngine body writer runs on a real dispatcher
        // while runTest's clock is virtual, so any multi-chunk read would
        // lose the race against the production stall timeout spuriously.
        // The stall test builds its own installer with a finite timeout.
        readStallTimeout = Duration.INFINITE,
    )

    private fun seedPartial(bytes: ByteArray) {
        partialFile.parentFile!!.mkdirs()
        partialFile.writeBytes(bytes)
    }

    // --- Happy path ---

    @Test
    fun downloadHappyPathInstallsVerifiedModel() = runTest {
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(
            listOf(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
                    "5359861c739e955e79d9a303bcbc70fb988958b1/${model.fileName}",
            ),
            requestedUrls,
        )
        assertEquals(listOf<String?>(null), requestedRanges, "a fresh download must not send a Range header")
        assertFalse(partialFile.exists(), "no partial should remain after promotion")
    }

    @Test
    fun installReplacesAnExistingFile() = runTest {
        installedFile.parentFile!!.mkdirs()
        installedFile.writeBytes(ByteArray(model.sizeBytes.toInt()) { 0x55 })
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
    }

    @Test
    fun fullLengthPartialIsPromotedWithoutTheNetwork() = runTest {
        // Process death between the digest gate and the rename leaves a
        // complete verified-size partial; it is hashed in place, never
        // re-downloaded.
        seedPartial(payload)
        installer { respond(ByteReadChannel("unreachable".encodeToByteArray()), HttpStatusCode.NotFound) }
            .install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(emptyList(), requestedUrls, "a complete partial must not touch the network")
    }

    // --- Integrity gate (fail closed) ---

    @Test
    fun digestMismatchDeletesThePartialAndRefuses() = runTest {
        // Same length, different bytes: only the digest can catch it, and
        // resuming from wrong bytes could never produce a right file.
        val tampered = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertFailsWith<SecurityException> {
            installer { respond(ByteReadChannel(tampered), HttpStatusCode.OK) }.install(model)
        }
        assertTrue(e.message.orEmpty().contains("failed verification"), "unexpected failure: $e")
        assertFalse(partialFile.exists(), "wrong bytes must not be kept for resume")
        assertFalse(installedFile.exists())
    }

    @Test
    fun oversizeStreamIsAbortedMidDownload() = runTest {
        val oversize = payload + ByteArray(512)
        val e = assertFailsWith<SecurityException> {
            installer { respond(ByteReadChannel(oversize), HttpStatusCode.OK) }.install(model)
        }
        assertTrue(e.message.orEmpty().contains("exceeded the pinned"), "unexpected failure: $e")
        assertFalse(partialFile.exists(), "an oversize stream is wrong bytes, not a resumable stop")
        assertFalse(installedFile.exists())
    }

    // --- Resume protocol ---

    @Test
    fun shortBodyKeepsThePartialForResume() = runTest {
        val e = assertFailsWith<Exception> {
            installer { respond(ByteReadChannel(payload.copyOfRange(0, 1000)), HttpStatusCode.OK) }
                .install(model)
        }
        assertTrue(e.message.orEmpty().contains("partial kept for resume"), "unexpected failure: $e")
        assertEquals(1000L, partialFile.length(), "the early-stopped bytes are the resume seed")
        assertFalse(installedFile.exists())
    }

    @Test
    fun resumeCompletesFromAPartial() = runTest {
        seedPartial(payload.copyOfRange(0, 1000))
        installer {
            respond(
                ByteReadChannel(payload.copyOfRange(1000, payload.size)),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 1000-${payload.size - 1}/${payload.size}"),
            )
        }.install(model)
        assertContentEquals(payload, installedFile.readBytes(), "resumed file must hash-verify whole")
        assertEquals(listOf<String?>("bytes=1000-"), requestedRanges)
        assertFalse(partialFile.exists())
    }

    @Test
    fun resumeIgnoredByTheServerRestartsClean() = runTest {
        // A server (or the CDN behind the redirect) that ignores Range
        // answers 200 with the full body; appending that to the partial
        // would corrupt it, so the install restarts from zero.
        seedPartial(payload.copyOfRange(0, 1000))
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(listOf<String?>("bytes=1000-", null), requestedRanges, "second attempt must be a clean GET")
    }

    @Test
    fun wrongContentRangeStartRestartsClean() = runTest {
        seedPartial(payload.copyOfRange(0, 1000))
        var first = true
        installer {
            if (first) {
                first = false
                respond(
                    ByteReadChannel(payload),
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes 0-${payload.size - 1}/${payload.size}"),
                )
            } else {
                respond(ByteReadChannel(payload), HttpStatusCode.OK)
            }
        }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(2, requestedUrls.size, "a mis-offset 206 must not be appended")
    }

    // --- Transport failure behavior ---

    @Test
    fun httpErrorFailsAndInstallsNothing() = runTest {
        val e = assertFailsWith<Exception> {
            installer { respond(ByteReadChannel("gone".encodeToByteArray()), HttpStatusCode.NotFound) }
                .install(model)
        }
        assertTrue(e.message.orEmpty().contains("HTTP 404"), "unexpected failure: $e")
        assertFalse(installedFile.exists())
        assertFalse(partialFile.exists())
    }

    @Test
    fun stalledDownloadFailsAndKeepsThePartial() = runTest {
        // A channel that serves a few bytes and then goes quiet, like a
        // dead socket the peer never closes. Finite timeout here where
        // every other test injects INFINITE to opt out; the timer runs on
        // runTest's virtual clock, so the 30 virtual seconds are free.
        val stalled = ByteChannel(autoFlush = true)
        stalled.writeFully(ByteArray(10))
        val stallInstaller = ModelFileInstaller(
            httpClient = HttpClient(MockEngine { respond(stalled, HttpStatusCode.OK) }),
            modelsDir = modelsDir,
            readStallTimeout = 30.seconds,
        )
        val e = assertFailsWith<Exception> { stallInstaller.install(model) }
        assertTrue(e.message.orEmpty().contains("stalled"), "unexpected failure: $e")
        assertEquals(10L, partialFile.length(), "a stall is a stopped transfer, not wrong bytes")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationLeavesAResumablePartial() = runTest {
        val open = ByteChannel(autoFlush = true)
        open.writeFully(ByteArray(1024))
        val job = launch {
            installer { respond(open, HttpStatusCode.OK) }.install(model)
        }
        // The body arrives from a real MockEngine thread while the install
        // coroutine runs on runTest's manually pumped dispatcher, so pump
        // until the transfer has visibly reached the partial before
        // cancelling; bounded so a hang fails the test instead of wedging
        // the suite.
        val deadline = System.currentTimeMillis() + 5_000
        while (partialFile.length() < 1024 && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        job.cancelAndJoin()
        assertEquals(1024L, partialFile.length(), "cancelled bytes are the resume seed for the retry")
        assertFalse(installedFile.exists())
    }

    // --- Load-time verification ---

    @Test
    fun verifyOnLoadAcceptsAGoodInstall() = runTest {
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertTrue(installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.verifyOnLoad(model))
        assertContentEquals(payload, installedFile.readBytes(), "verification must not disturb the file")
    }

    @Test
    fun verifyOnLoadQuarantinesATamperedFile() = runTest {
        val inst = installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }
        inst.install(model)
        // Same size, different content: exactly what the cheap size check
        // cannot see and only the load-time re-hash catches.
        val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        installedFile.writeBytes(tampered)
        assertFalse(inst.verifyOnLoad(model))
        assertFalse(installedFile.exists(), "a failed file must never stay loadable")
        assertContentEquals(tampered, quarantineFile.readBytes(), "the evidence is kept, moved aside")
    }

    @Test
    fun verifyOnLoadOnAMissingInstallIsFalse() = runTest {
        assertFalse(installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.verifyOnLoad(model))
    }

    // --- On-disk contract ---

    @Test
    fun onDiskNamesAreAStableContract() {
        // Existing installs and partials on user devices carry these exact
        // names; a rename must be a conscious migration, not a refactor
        // side effect.
        assertEquals(".staging", ModelFileInstaller.STAGING_DIR)
        assertEquals(".partial", ModelFileInstaller.PARTIAL_SUFFIX)
        assertEquals(".quarantined", ModelFileInstaller.QUARANTINE_SUFFIX)
    }
}
