package coredevices.coreapp.model

import com.anopticlabs.gravel.models.ChatFormat
import com.anopticlabs.gravel.models.LlmModel
import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.ModelStorageException
import com.anopticlabs.gravel.network.EndlessResponse
import coredevices.util.models.WhisperModel
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.models.WhisperTier
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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the verified single-file model install pipeline against a mock
 * HTTP engine: the happy path (commit-pinned URL, digest gate, atomic
 * promotion), the resume protocol (exact-206 continuation, clean restart
 * on anything else), the fail-closed boundary (complete-but-wrong bytes
 * delete the partial; stopped-early keeps it), transport retries, the
 * storage checks, staging cleanup, and the load-time re-verification with
 * quarantine.
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
        tier = WhisperTier.Base,
        displayName = "Test model",
        fileName = "ggml-test.bin",
        sha256 = sha256Hex(bytes),
        sizeBytes = sizeBytes,
        minRamBytes = 1,
        multilingual = false,
    )

    private val model = modelFor(payload)

    private val installedFile: File get() = modelsDir.resolve(model.id).resolve(model.fileName)
    private val partialFile: File get() = modelsDir.resolve(ModelFileInstaller.STAGING_DIR)
        .resolve("${model.id}.${model.sha256.take(16)}${ModelFileInstaller.PARTIAL_SUFFIX}")
    private val quarantineFile: File get() =
        modelsDir.resolve(ModelFileInstaller.STAGING_DIR).resolve("${model.id}${ModelFileInstaller.QUARANTINE_SUFFIX}")

    private val requestedRanges = mutableListOf<String?>()
    private val requestedUrls = mutableListOf<String>()

    private fun installer(
        usableSpace: (File) -> Long = { Long.MAX_VALUE },
        retryBackoff: Duration = Duration.ZERO,
        openPartial: (File, Boolean) -> OutputStream = { file, append -> FileOutputStream(file, append) },
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
        retryBackoff = retryBackoff,
        usableSpace = usableSpace,
        openPartial = openPartial,
    )

    /** Answers the requests in order, one handler per request; a request past the list fails the test. */
    private fun sequence(
        vararg responses: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        var next = 0
        return { request -> responses.getOrNull(next++)?.invoke(this, request) ?: error("unexpected request ${next}") }
    }

    private fun serviceUnavailable(): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.ServiceUnavailable) }

    private fun firstBytes(
        count: Int,
        bytes: ByteArray = payload,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(ByteReadChannel(bytes.copyOfRange(0, count)), HttpStatusCode.OK) }

    private fun restFrom(
        offset: Int,
        bytes: ByteArray = payload,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(
            ByteReadChannel(bytes.copyOfRange(offset, bytes.size)),
            HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, "bytes $offset-${bytes.size - 1}/${bytes.size}"),
        )
    }

    private fun seedPartial(bytes: ByteArray) {
        partialFile.parentFile!!.mkdirs()
        partialFile.writeBytes(bytes)
    }

    @Test
    fun theBytesKeptForAResumeAreCountedAndCanBeDiscarded() = runTest {
        val installer = installer { error("no request expected") }
        assertEquals(0L, installer.partialBytes(model))
        seedPartial(payload.copyOf(1000))
        assertEquals(1000L, installer.partialBytes(model))
        installer.discardPartial(model)
        assertFalse(partialFile.exists())
        assertEquals(0L, installer.partialBytes(model))
    }

    @Test
    fun aDownloadAfterADiscardStartsFromTheBeginning() = runTest {
        seedPartial(payload.copyOf(1000))
        val installer = installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }
        installer.discardPartial(model)
        installer.install(model)
        assertEquals(listOf<String?>(null), requestedRanges)
        assertContentEquals(payload, installedFile.readBytes())
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

    @Test
    fun anEndlessOversizeStreamLeavesNoCopierRunning() = runTest {
        val server = EndlessResponse()
        assertFailsWith<SecurityException> {
            ModelFileInstaller(server.client, modelsDir, readStallTimeout = Duration.INFINITE).install(model)
        }
        server.assertNoCopierLeft()
    }

    @Test
    fun fullLengthWrongBytesPartialIsDeletedAndReDownloaded() = runTest {
        // A complete-size partial whose bytes are wrong (corruption, or a
        // prior tampered download that reached full length) must not be
        // promoted: it is deleted and a clean verified download replaces it.
        seedPartial(payload.copyOf().also { it[10] = (it[10] + 1).toByte() })
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(
            listOf<String?>(null),
            requestedRanges,
            "a wrong full-length partial must fall through to a clean GET, not a resume",
        )
        assertFalse(partialFile.exists())
    }

    @Test
    fun oversizePartialIsDeletedAndReDownloaded() = runTest {
        // A partial larger than the pin cannot be a valid prefix; it is
        // discarded and re-downloaded clean.
        seedPartial(payload + ByteArray(512))
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(listOf<String?>(null), requestedRanges, "an oversize partial must not be resumed")
        assertFalse(partialFile.exists())
    }

    // --- Resume protocol ---

    @Test
    fun shortBodiesWithoutProgressKeepThePartialForResume() = runTest {
        val emptyResume: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respond(
                ByteReadChannel(ByteArray(0)),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 1000-${payload.size - 1}/${payload.size}"),
            )
        }
        val e = assertFailsWith<Exception> {
            installer(handler = sequence(firstBytes(1000), *Array(ModelFileInstaller.MAX_STALLS - 1) { emptyResume }))
                .install(model)
        }
        assertTrue(e.message.orEmpty().contains("partial kept for resume"), "unexpected failure: $e")
        assertEquals(1000L, partialFile.length(), "the early-stopped bytes are the resume seed")
        assertEquals(listOf<String?>(null) + List(ModelFileInstaller.MAX_STALLS - 1) { "bytes=1000-" }, requestedRanges)
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
        assertEquals(1, requestedUrls.size, "a hard status is not retried")
        assertFalse(installedFile.exists())
        assertFalse(partialFile.exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stalledDownloadsRetryThenFailAndKeepThePartial() = runTest {
        // A channel that serves a few bytes and then goes quiet, like a
        // dead socket the peer never closes, and resumes that stay quiet.
        // Finite timeout here where every other test injects INFINITE to
        // opt out; the timer runs on runTest's virtual clock, so the 30
        // virtual seconds per attempt are free.
        val stalled = ByteChannel(autoFlush = true)
        stalled.writeFully(ByteArray(10))
        var requests = 0
        val stallInstaller = ModelFileInstaller(
            httpClient = HttpClient(MockEngine {
                if (requests++ == 0) {
                    respond(stalled, HttpStatusCode.OK)
                } else {
                    respond(
                        ByteChannel(autoFlush = true),
                        HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes 10-${payload.size - 1}/${payload.size}"),
                    )
                }
            }),
            modelsDir = modelsDir,
            readStallTimeout = 30.seconds,
            retryBackoff = Duration.ZERO,
        )
        var failure: Throwable? = null
        val installJob = launch {
            failure = runCatching { stallInstaller.install(model) }.exceptionOrNull()
        }
        // The seeded bytes reach the installer through a real-dispatcher
        // hop (bodyAsChannel returns a wrapper fed by a copy writer on the
        // client's own threads), while the stall timer sits on the virtual
        // clock, which jumps the whole 30s the moment this coroutine
        // suspends with the scheduler idle. On a loaded machine that jump
        // can outrun the copy and stall the download at 0 bytes, so pump
        // real time until the bytes have landed in the partial before
        // letting the clock move: runCurrent runs due work without
        // advancing virtual time, so the timer cannot fire during the
        // pump. Bounded so a hang fails the test instead of wedging the
        // suite.
        val deadline = System.currentTimeMillis() + 5_000
        while (partialFile.length() < 10 && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertEquals(10L, partialFile.length(), "seeded bytes must land before the stall timer may fire")
        // Only quiet-channel waiting is left, so joining idles the
        // scheduler, the clock jumps to the timer, and the stall fires.
        installJob.join()
        val e = assertNotNull(failure, "the install must fail once the resumes stay quiet")
        assertTrue(e.cause?.message.orEmpty().contains("stalled"), "unexpected failure: $e")
        assertEquals(ModelFileInstaller.MAX_STALLS, requests, "every stall is retried until five add too little")
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

    // --- Retries ---

    @Test
    fun aDroppedTransferResumesInsideOneInstall() = runTest {
        installer(handler = sequence(firstBytes(1000), restFrom(1000))).install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(listOf<String?>(null, "bytes=1000-"), requestedRanges)
    }

    @Test
    fun aRetriedStatusIsRetried() = runTest {
        installer(handler = sequence(serviceUnavailable(), { respond(ByteReadChannel(payload), HttpStatusCode.OK) }))
            .install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(2, requestedUrls.size)
    }

    @Test
    fun attemptsWithoutProgressGiveUpAfterMaxStalls() = runTest {
        val e = assertFailsWith<Exception> {
            installer { respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.ServiceUnavailable) }.install(model)
        }
        assertTrue(e.message.orEmpty().contains("without progress"), "unexpected failure: $e")
        assertEquals(ModelFileInstaller.MAX_STALLS, requestedUrls.size)
        assertFalse(installedFile.exists())
    }

    @Test
    fun progressResetsTheStallCount() = runTest {
        val large = ByteArray(3 * 1024 * 1024) { (it % 253).toByte() }
        val largeModel = modelFor(large)
        val progressed = (ModelFileInstaller.MIN_PROGRESS_BYTES + 1).toInt()
        val almostGivingUp = Array(ModelFileInstaller.MAX_STALLS - 1) { serviceUnavailable() }
        installer(
            handler = sequence(*almostGivingUp, firstBytes(progressed, large), *almostGivingUp, restFrom(progressed, large)),
        ).install(largeModel)
        assertContentEquals(large, installedFile.readBytes())
        assertEquals(2 * ModelFileInstaller.MAX_STALLS, requestedUrls.size)
    }

    @Test
    fun attemptsAddingTooLittleCountAsStalls() = runTest {
        // One byte per answer: progress, but not enough to hold the link open forever.
        val e = assertFailsWith<Exception> {
            installer { request ->
                val offset = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                if (offset == 0) {
                    respond(ByteReadChannel(payload.copyOfRange(0, 1)), HttpStatusCode.OK)
                } else {
                    respond(
                        ByteReadChannel(payload.copyOfRange(offset, offset + 1)),
                        HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes $offset-${payload.size - 1}/${payload.size}"),
                    )
                }
            }.install(model)
        }
        assertTrue(e.message.orEmpty().contains("without progress"), "unexpected failure: $e")
        assertEquals(ModelFileInstaller.MAX_STALLS, requestedUrls.size)
        assertEquals(ModelFileInstaller.MAX_STALLS.toLong(), partialFile.length())
    }

    @Test
    fun retriesWaitLongerWhileNothingArrives() = runTest {
        assertFailsWith<Exception> { installer(retryBackoff = 1.seconds, handler = serviceUnavailable()).install(model) }
        assertEquals(15_000L, testScheduler.currentTime, "four waits of 1, 2, 4 and 8 seconds")
    }

    @Test
    fun aServerThatKeepsIgnoringResumesFails() = runTest {
        // Every resume is answered with a whole-file 200 and every fresh
        // download is cut short, so each restart throws its progress away.
        seedPartial(payload.copyOfRange(0, 1000))
        val e = assertFailsWith<Exception> {
            installer { request ->
                if (request.headers[HttpHeaders.Range] != null) {
                    respond(ByteReadChannel(payload), HttpStatusCode.OK)
                } else {
                    respond(ByteReadChannel(payload.copyOfRange(0, 1000)), HttpStatusCode.OK)
                }
            }.install(model)
        }
        assertTrue(e.message.orEmpty().contains("ignoring resume requests"), "unexpected failure: $e")
        assertEquals(2 * ModelFileInstaller.MAX_RESTARTS + 1, requestedUrls.size)
        assertFalse(installedFile.exists())
    }

    @Test
    fun retryDelaysAreShortAfterProgressAndDoubleWithoutIt() {
        val delays = (0 until ModelFileInstaller.MAX_STALLS).map { ModelFileInstaller.retryDelay(it, 1.seconds) }
        assertEquals(listOf(250.milliseconds, 1.seconds, 2.seconds, 4.seconds, 8.seconds), delays)
    }

    @Test
    fun onlyTransientStatusesAreRetried() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504, 599)) {
            assertTrue(ModelFileInstaller.isRetriedStatus(status), "$status")
        }
        for (status in listOf(400, 401, 403, 404, 410, 416, 451)) {
            assertFalse(ModelFileInstaller.isRetriedStatus(status), "$status")
        }
    }

    // --- Storage ---

    @Test
    fun notEnoughFreeSpaceFailsBeforeAnyRequest() = runTest {
        val e = assertFailsWith<ModelStorageException> {
            installer(usableSpace = { model.sizeBytes - 1 }) { respond(ByteReadChannel(payload), HttpStatusCode.OK) }
                .install(model)
        }
        assertTrue(e.outOfSpace)
        assertEquals(emptyList(), requestedUrls)
    }

    @Test
    fun freeSpaceIsCheckedForTheRemainingBytesOnly() = runTest {
        seedPartial(payload.copyOfRange(0, 1000))
        installer(usableSpace = { model.sizeBytes - 1000 }, handler = restFrom(1000)).install(model)
        assertContentEquals(payload, installedFile.readBytes())
    }

    @Test
    fun aPartialThatCannotBeOpenedFailsWithoutRetry() = runTest {
        val e = assertFailsWith<ModelStorageException> {
            installer(openPartial = { _, _ -> throw FileNotFoundException("denied") }) {
                respond(ByteReadChannel(payload), HttpStatusCode.OK)
            }.install(model)
        }
        assertFalse(e.outOfSpace, "a refused open is not a full disk")
        assertEquals(1, requestedUrls.size, "a disk failure is not retried")
    }

    @Test
    fun aWriteFailureMidTransferFailsWithoutRetry() = runTest {
        val failingWrite = { file: File, append: Boolean ->
            object : FileOutputStream(file, append) {
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("EIO")
            }
        }
        assertFailsWith<ModelStorageException> {
            installer(openPartial = failingWrite) { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        }
        assertEquals(1, requestedUrls.size)
        assertFalse(installedFile.exists())
    }

    @Test
    fun aCloseFailureFailsWithoutRetry() = runTest {
        val failingClose = { file: File, append: Boolean ->
            object : FileOutputStream(file, append) {
                override fun close() {
                    super.close()
                    throw IOException("EIO on close")
                }
            }
        }
        assertFailsWith<ModelStorageException> {
            installer(openPartial = failingClose) { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        }
        assertEquals(1, requestedUrls.size)
        assertFalse(installedFile.exists())
    }

    @Test
    fun aFailedMoveIntoPlaceKeepsTheVerifiedPartial() = runTest {
        // A file where the model's directory belongs makes the final move fail.
        modelsDir.resolve(model.id).writeText("in the way")
        assertFailsWith<ModelStorageException> {
            installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(model)
        }
        assertEquals(1, requestedUrls.size)
        assertEquals(model.sizeBytes, partialFile.length())

        modelsDir.resolve(model.id).delete()
        installer { error("a verified partial needs no request") }.install(model)
        assertContentEquals(payload, installedFile.readBytes())
    }

    // --- Partial on disk vs digest ---

    @Test
    fun aPartialChangedBetweenAttemptsRestartsClean() = runTest {
        installer(
            handler = sequence(
                firstBytes(1000),
                { partialFile.delete(); serviceUnavailable()(this, it) },
                { respond(ByteReadChannel(payload), HttpStatusCode.OK) },
            ),
        ).install(model)
        assertContentEquals(payload, installedFile.readBytes())
        assertEquals(listOf<String?>(null, "bytes=1000-", null), requestedRanges)
    }

    @Test
    fun aPartialChangedDuringTheLastAttemptFailsTheGate() = runTest {
        val e = assertFailsWith<SecurityException> {
            installer(handler = sequence(firstBytes(1000), { partialFile.delete(); restFrom(1000)(this, it) }))
                .install(model)
        }
        assertTrue(e.message.orEmpty().contains("got ${payload.size - 1000} bytes"), "unexpected failure: $e")
        assertFalse(installedFile.exists())
        assertFalse(partialFile.exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aTransferThatStallsAfterItsLastByteIsPromotedWithoutAnotherRequest() = runTest {
        // Every byte arrives but the connection never closes, so the read
        // after the last byte stalls; see the stall test for the pumping.
        val open = ByteChannel(autoFlush = true)
        open.writeFully(payload)
        var requests = 0
        val stallInstaller = ModelFileInstaller(
            httpClient = HttpClient(MockEngine {
                requests++
                respond(open, HttpStatusCode.OK)
            }),
            modelsDir = modelsDir,
            readStallTimeout = 30.seconds,
            retryBackoff = Duration.ZERO,
        )
        var failure: Throwable? = null
        val installJob = launch { failure = runCatching { stallInstaller.install(model) }.exceptionOrNull() }
        val deadline = System.currentTimeMillis() + 5_000
        while (partialFile.length() < payload.size && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertEquals(payload.size.toLong(), partialFile.length(), "every byte must land before the stall timer may fire")
        installJob.join()
        assertEquals(null, failure)
        assertEquals(1, requests)
        assertContentEquals(payload, installedFile.readBytes())
    }

    // --- Failure classes ---

    @Test
    fun certificateFailuresAreNotRetried() = runTest {
        assertFailsWith<SSLHandshakeException> {
            installer {
                throw SSLHandshakeException("untrusted").apply { initCause(CertificateException("no trust anchor")) }
            }.install(model)
        }
        assertEquals(1, requestedUrls.size)
    }

    @Test
    fun onlyTransportFailuresAreTransient() {
        assertTrue(ModelFileInstaller.isTransientFailure(SocketTimeoutException("read timed out")))
        assertTrue(ModelFileInstaller.isTransientFailure(SSLException("connection reset")))
        assertFalse(ModelFileInstaller.isTransientFailure(SSLPeerUnverifiedException("hostname mismatch")))
        assertFalse(
            ModelFileInstaller.isTransientFailure(
                SSLHandshakeException("untrusted").apply { initCause(CertPathValidatorException("expired")) },
            ),
        )
        assertFalse(ModelFileInstaller.isTransientFailure(ModelStorageException("disk")))
        assertFalse(ModelFileInstaller.isTransientFailure(SecurityException("pin")))
    }

    // --- Catalog generality and staging cleanup ---

    @Test
    fun aTextModelInstallsFromItsOwnRepositoryCommit() = runTest {
        val textModel = LlmModel(
            id = "qwen3-1_7b-q4_k_m",
            displayName = "Test text model",
            fileName = "test.gguf",
            sha256 = sha256Hex(payload),
            sizeBytes = payload.size.toLong(),
            minRamBytes = 1,
            license = "Apache-2.0",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            commit = "d7f544eead698dbd1f15126ef60b45a1e1933222",
            chatFormat = ChatFormat.ChatMl,
            promptSuffix = "",
        )
        installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }.install(textModel)
        assertEquals(
            listOf("https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/test.gguf"),
            requestedUrls,
        )
        assertContentEquals(payload, modelsDir.resolve("qwen3-1_7b-q4_k_m").resolve("test.gguf").readBytes())
    }

    @Test
    fun stagingKeepsOnlyCurrentCatalogPartialsAndQuarantines() {
        val staging = modelsDir.resolve(ModelFileInstaller.STAGING_DIR).also { it.mkdirs() }
        val inst = installer { respond(ByteReadChannel(payload), HttpStatusCode.OK) }
        val whisper = WhisperModelCatalog.byId("whisper-base-en")!!
        val text = LlmModelCatalog.byId("qwen3-1_7b-q4_k_m")!!
        val kept = listOf(inst.partialFile(whisper).name, "whisper-base-en.quarantined", inst.partialFile(text).name)
        val orphans = listOf(
            "whisper-base-en.partial", // an earlier installer's name, which carried no pin
            "whisper-base-en.0123456789abcdef.partial", // a previous pin's bytes
            "removed-model.0123456789abcdef.partial",
            "removed-model.quarantined",
            "stray.tmp",
        )
        (kept + orphans).forEach { staging.resolve(it).writeText("x") }
        staging.resolve(inst.partialFile(WhisperModelCatalog.byId("whisper-small-en")!!).name).mkdirs()

        val pruned = inst.pruneOrphanedStaging()

        assertEquals(orphans.size + 1, pruned.size)
        assertEquals(kept.toSet(), staging.list()!!.toSet())
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
        assertEquals(
            "whisper-base-en.${model.sha256.take(16)}.partial",
            installer { error("no request") }.partialFile(model).name,
        )
        assertEquals(".quarantined", ModelFileInstaller.QUARANTINE_SUFFIX)
    }
}
