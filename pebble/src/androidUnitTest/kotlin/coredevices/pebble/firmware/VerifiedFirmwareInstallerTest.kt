package coredevices.pebble.firmware

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
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdateErrorStarting
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.util.Crc32Calculator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the verified install pipeline end to end against synthetic PBZ
 * archives: every fail-closed branch (missing expectation, https, checksum,
 * size, stall, manifest hardware/tag/type, inner CRC) must refuse the
 * install, never call sideload, and leave no file behind; the happy path
 * must hand libpebble3 the exact verified bytes. Lives in androidUnitTest
 * because the fixtures are built with java.util.zip.
 */
class VerifiedFirmwareInstallerTest {

    private val tempDir = Path(Files.createTempDirectory("fork-fw-test").toString())
    private val url = "https://release-assets.example.com/normal_asterix_v4.31.1.pbz"

    // --- Synthetic PBZ fixtures ---

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun crcOf(bytes: ByteArray): Long =
        Crc32Calculator().apply { addBytes(bytes.toUByteArray()) }.finalize().toLong()

    private fun manifestJson(
        hwrev: String,
        firmware: ByteArray,
        resources: ByteArray,
        versionTag: String?,
        type: String,
        slot: Int?,
        corruptCrc: Boolean,
    ): ByteArray = buildString {
        append("""{"manifestVersion":2,"generatedAt":1785364975,"debug":{},"firmware":{""")
        append(""""name":"pebbleos.bin","type":"$type","timestamp":1785364659,"commit":"abc123",""")
        append(""""hwrev":"$hwrev","size":${firmware.size},"crc":${crcOf(firmware) + if (corruptCrc) 1 else 0}""")
        if (versionTag != null) append(""","versionTag":"$versionTag"""")
        if (slot != null) append(""","slot":$slot""")
        append("""},"resources":{"name":"system_resources.pbpack","timestamp":1785364659,""")
        append(""""size":${resources.size},"crc":${crcOf(resources)}},"type":"firmware"}""")
    }.encodeToByteArray()

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

    private val firmwareBytes = ByteArray(600) { (it % 251).toByte() }
    private val resourceBytes = ByteArray(400) { (it % 127).toByte() }

    private fun singleSlotPbz(
        hwrev: String = "asterix",
        versionTag: String? = "v4.31.1",
        type: String = "normal",
        corruptCrc: Boolean = false,
    ): ByteArray = zip(
        mapOf(
            "manifest.json" to manifestJson(hwrev, firmwareBytes, resourceBytes, versionTag, type, slot = null, corruptCrc = corruptCrc),
            "pebbleos.bin" to firmwareBytes,
            "system_resources.pbpack" to resourceBytes,
        ),
    )

    private fun dualSlotPbz(hwrevSlot0: String, hwrevSlot1: String): ByteArray = zip(
        mapOf(
            "slot0/manifest.json" to manifestJson(hwrevSlot0, firmwareBytes, resourceBytes, "v4.31.1", "normal", slot = 0, corruptCrc = false),
            "slot0/pebbleos.bin" to firmwareBytes,
            "slot0/system_resources.pbpack" to resourceBytes,
            "slot1/manifest.json" to manifestJson(hwrevSlot1, firmwareBytes, resourceBytes, "v4.31.1", "normal", slot = 1, corruptCrc = false),
            "slot1/pebbleos.bin" to firmwareBytes,
            "slot1/system_resources.pbpack" to resourceBytes,
        ),
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // --- Harness ---

    private val requests = AtomicInteger(0)
    private val expectations = FirmwareArtifactExpectations()
    private val watchStates = MutableStateFlow<FirmwareUpdateStatus?>(FirmwareUpdateStatus.NotInProgress.Idle())

    private var sideloadedBytes: ByteArray? = null
    private var sideloadedPath: Path? = null
    private var onSideload: (Path) -> Unit = {}

    private fun installer(
        scope: CoroutineScope,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): VerifiedFirmwareInstaller = VerifiedFirmwareInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requests.incrementAndGet()
            handler(request)
        }),
        expectations = expectations,
        downloadDirectory = { tempDir },
        watchUpdateStates = { watchStates },
        scope = scope,
    )

    private fun target(
        key: String = "watch1",
        platform: WatchHardwarePlatform = WatchHardwarePlatform.CORE_ASTERIX,
    ) = VerifiedFirmwareInstaller.InstallTarget(
        identifierKey = key,
        platform = platform,
        sideload = { path ->
            sideloadedPath = path
            // Read immediately: the installer deletes the file once the
            // (test-driven) transfer reaches a terminal state.
            sideloadedBytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
            onSideload(path)
        },
    )

    private fun update(tag: String = "v4.31.1") = FirmwareUpdateCheckResult.FoundUpdate(
        version = testFwVersion(tag),
        url = url,
        notes = "",
    )

    private suspend fun record(pbz: ByteArray, tag: String = "v4.31.1", sha256: String = sha256Hex(pbz), size: Long? = pbz.size.toLong()) {
        expectations.record(url, ExpectedFirmwareArtifact(sha256, size, tag))
    }

    private fun serving(pbz: ByteArray): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(ByteReadChannel(pbz), HttpStatusCode.OK) }

    private fun forkFilesLeft(): List<Path> =
        SystemFileSystem.list(tempDir).filter { it.name.startsWith("fork-fw-") }

    private fun handoffSucceeds() {
        onSideload = {
            watchStates.value = FirmwareUpdateStatus.WaitingForReboot(update())
        }
    }

    // --- Tests ---

    @Test
    fun happyPathVerifiesAndSideloadsExactBytes() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        handoffSucceeds()
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        assertNotNull(sideloadedPath)
        assertContentEquals(pbz, sideloadedBytes)
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
        assertTrue(forkFilesLeft().isEmpty(), "temp file should be deleted after the transfer ends")
    }

    @Test
    fun staleDownloadsAreSweptBeforeTheFirstDownload() = runTest {
        val stale = Path(tempDir, "fork-fw-stale-999.pbz")
        SystemFileSystem.sink(stale).buffered().use { it.write(ByteArray(10)) }
        val pbz = singleSlotPbz()
        record(pbz)
        handoffSucceeds()
        installer(backgroundScope, serving(pbz)).runInstall(target(), update())
        assertTrue(forkFilesLeft().isEmpty(), "stale file from a previous process should be swept")
    }

    @Test
    fun missingExpectationFailsClosedWithoutTouchingTheNetwork() = runTest {
        val installer = installer(backgroundScope, serving(singleSlotPbz()))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "no integrity data")
        assertEquals(0, requests.get())
        assertNull(sideloadedPath)
    }

    @Test
    fun nonHttpsUrlIsRefused() = runTest {
        val httpUrl = "http://release-assets.example.com/fw.pbz"
        val pbz = singleSlotPbz()
        expectations.record(httpUrl, ExpectedFirmwareArtifact(sha256Hex(pbz), pbz.size.toLong(), "v4.31.1"))
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(
            target(),
            FirmwareUpdateCheckResult.FoundUpdate(testFwVersion("v4.31.1"), httpUrl, ""),
        )
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "non-https")
        assertEquals(0, requests.get())
    }

    @Test
    fun refusesWhenAnUpstreamUpdateIsAlreadyRunning() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        // The gate reads the live per-watch state flow, so an update that
        // started after the device snapshot was taken still blocks.
        watchStates.value = FirmwareUpdateStatus.WaitingToStart(update())
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "already in progress")
        assertEquals(0, requests.get())
    }

    @Test
    fun vanishedWatchIsRefusedBeforeDownloading() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        watchStates.value = null
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "not connected")
        assertEquals(0, requests.get())
    }

    @Test
    fun checksumMismatchRefusesAndDeletesTheFile() = runTest {
        val pbz = singleSlotPbz()
        record(pbz, sha256 = "0".repeat(64))
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "checksum mismatch")
        assertNull(sideloadedPath)
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun shortDownloadFailsTheSizeCheck() = runTest {
        val pbz = singleSlotPbz()
        record(pbz, size = pbz.size.toLong() + 5)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "size mismatch")
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun oversizeStreamIsAbortedMidDownload() = runTest {
        val pbz = singleSlotPbz()
        record(pbz, size = 10L)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "larger than expected")
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun nullSizeExpectationInstallsAndSkipsOnlyTheSizeChecks() = runTest {
        // Cohorts records no size, so the null-size path is the entire
        // install path for legacy watches: the cap falls back to the
        // constant, the exact-size check is skipped, nothing else loosens.
        val pbz = singleSlotPbz()
        record(pbz, size = null)
        handoffSucceeds()
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        assertNotNull(sideloadedPath)
        assertContentEquals(pbz, sideloadedBytes)
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun nullSizeExpectationStillEnforcesTheChecksum() = runTest {
        val pbz = singleSlotPbz()
        record(pbz, sha256 = "0".repeat(64), size = null)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "checksum mismatch")
        assertNull(sideloadedPath)
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun nonZipPayloadFailsClosedAndDeletesTheFile() = runTest {
        // The expectation hash is computed over whatever the source
        // published, so a garbage artifact passes the transport layer and
        // only the parse layer can refuse it.
        val garbage = ByteArray(700) { (it % 31).toByte() }
        record(garbage)
        val installer = installer(backgroundScope, serving(garbage))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "could not verify")
        assertNull(sideloadedPath)
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun zipWithoutAManifestFailsClosedAndDeletesTheFile() = runTest {
        val pbz = zip(mapOf("pebbleos.bin" to firmwareBytes))
        record(pbz)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "could not verify")
        assertNull(sideloadedPath)
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun httpErrorFails() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        val installer = installer(backgroundScope) { respond("gone", HttpStatusCode.NotFound) }
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "404")
    }

    @Test
    fun stalledDownloadFails() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        val installer = installer(backgroundScope) {
            val channel = ByteChannel()
            channel.writeFully(pbz, 0, 16)
            channel.flush()
            // Never closed: the per-read stall timeout must fire.
            respond(channel, HttpStatusCode.OK)
        }
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "stalled")
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun wrongHardwareRevisionIsRefused() = runTest {
        val pbz = singleSlotPbz(hwrev = "silk")
        record(pbz)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "not this watch")
        assertNull(sideloadedPath)
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun versionTagMismatchIsRefused() = runTest {
        val pbz = singleSlotPbz(versionTag = "v9.9.9")
        record(pbz)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "does not match release")
        assertNull(sideloadedPath)
    }

    @Test
    fun taglessManifestSkipsTheTagCheckOnly() = runTest {
        // Sources that never wrote a versionTag must not be broken by the
        // tag cross-check; the byte hash still pins the exact content.
        val pbz = singleSlotPbz(versionTag = null)
        record(pbz)
        handoffSucceeds()
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        assertNotNull(sideloadedPath)
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
    }

    @Test
    fun recoveryTypeArchiveIsRefused() = runTest {
        // The checkers only ever offer normal firmware; a recovery archive
        // reaching this pipeline means the selection went wrong.
        val pbz = singleSlotPbz(type = "recovery")
        record(pbz)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "unexpected firmware type")
    }

    @Test
    fun innerCrcMismatchIsRefusedEvenWhenTheHashMatches() = runTest {
        // The expectation hash is computed over the corrupt archive, so the
        // download-integrity layer passes and only the CRC cross-check can
        // catch the source-corrupt content.
        val pbz = singleSlotPbz(corruptCrc = true)
        record(pbz)
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "CRC")
        assertNull(sideloadedPath)
    }

    @Test
    fun dualSlotArchivesCheckEveryManifest() = runTest {
        // A wrong hardware revision hiding in slot1 must fail even though
        // slot0 is fine.
        val bad = dualSlotPbz(hwrevSlot0 = "asterix", hwrevSlot1 = "silk")
        record(bad)
        val installer = installer(backgroundScope, serving(bad))
        installer.runInstall(target(), update())
        assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertNull(sideloadedPath)

        val good = dualSlotPbz(hwrevSlot0 = "asterix", hwrevSlot1 = "asterix")
        expectations.record(url, ExpectedFirmwareArtifact(sha256Hex(good), good.size.toLong(), "v4.31.1"))
        handoffSucceeds()
        installer(backgroundScope, serving(good)).runInstall(target(key = "watch2"), update())
        assertNotNull(sideloadedPath)
    }

    @Test
    fun handoffThatNeverStartsFailsAndCleansUp() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        onSideload = {} // upstream stays Idle: silent mutex rejection
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "did not start")
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun upstreamErrorStartingIsNotDoubleReported() = runTest {
        // libpebble3 already renders ErrorStarting in the watch state line;
        // the fork state must go Idle instead of showing a second failure.
        val pbz = singleSlotPbz()
        record(pbz)
        onSideload = {
            watchStates.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
                FirmwareUpdateErrorStarting.ErrorParsingPbz,
            )
        }
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun secondInstallForTheSameWatchIsIgnoredWhileOneIsRunning() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        // The engine handles requests off the test scheduler, so completion
        // is signalled from the handler instead of assuming scheduler order.
        val firstRequestStarted = CompletableDeferred<Unit>()
        val installer = installer(backgroundScope) {
            firstRequestStarted.complete(Unit)
            val channel = ByteChannel()
            channel.writeFully(pbz, 0, 16)
            channel.flush()
            respond(channel, HttpStatusCode.OK)
        }
        val first = backgroundScope.launch { installer.runInstall(target(), update()) }
        firstRequestStarted.await()
        assertEquals(1, requests.get())
        installer.runInstall(target(), update())
        assertEquals(1, requests.get(), "second install must not start a second download")
        first.cancelAndJoin()
        // Cancellation cleanup: the partial download is deleted and the fork
        // state resets, so the UI cannot stay stuck on Downloading.
        assertTrue(forkFilesLeft().isEmpty(), "cancelled install must not leak its partial file")
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
    }

    @Test
    fun distinctWatchesCanInstallConcurrently() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        val bothRequested = CompletableDeferred<Unit>()
        val installer = installer(backgroundScope) {
            if (requests.get() >= 2) bothRequested.complete(Unit)
            val channel = ByteChannel()
            channel.writeFully(pbz, 0, 16)
            channel.flush()
            respond(channel, HttpStatusCode.OK)
        }
        val first = backgroundScope.launch { installer.runInstall(target(key = "watchA"), update()) }
        val second = backgroundScope.launch { installer.runInstall(target(key = "watchB"), update()) }
        bothRequested.await()
        assertEquals(2, requests.get(), "each watch gets its own download")
        first.cancelAndJoin()
        second.cancelAndJoin()
        assertTrue(forkFilesLeft().isEmpty(), "cancelled installs must not leak partial files")
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watchA"))
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watchB"))
    }

    @Test
    fun staleErrorStartingFromAPreviousAttemptIsNotReadAsThisInstallsOutcome() = runTest {
        // Upstream keeps ErrorStarting until the NEXT sideload gets past its
        // parse, and the state flow replays it to new collectors; the
        // handoff watcher must not treat the replay as this attempt's
        // outcome, and must not delete the archive while the freshly
        // launched sideload coroutine still needs it.
        val pbz = singleSlotPbz()
        record(pbz)
        watchStates.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
            FirmwareUpdateErrorStarting.ErrorParsingPbz,
        )
        val sideloadStarted = CompletableDeferred<Unit>()
        onSideload = { sideloadStarted.complete(Unit) } // upstream parse still running
        val installer = installer(backgroundScope, serving(pbz))
        val install = backgroundScope.launch { installer.runInstall(target(), update()) }
        // The scheduler is single threaded, so once await() resumes here the
        // install coroutine has already seen the replayed stale value and is
        // suspended waiting for a genuinely new emission.
        sideloadStarted.await()
        assertEquals(1, forkFilesLeft().size, "file must survive while the sideload parse runs")
        assertEquals(ForkFirmwareInstallState.HandedOff, installer.stateValueFor("watch1"))
        // The retry's parse succeeds and the transfer runs to completion.
        watchStates.value = FirmwareUpdateStatus.WaitingToStart(update())
        watchStates.value = FirmwareUpdateStatus.WaitingForReboot(update())
        install.join()
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun staleErrorStartingWithASilentRejectionFailsAsNotStarted() = runTest {
        // Degraded corner of the stale-state defense: the retry fails with
        // an identical error, which a StateFlow cannot re-emit, so the only
        // safe outcome is the start timeout (fail closed, file cleaned up
        // after the upstream parse window, not during it).
        val pbz = singleSlotPbz()
        record(pbz)
        watchStates.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
            FirmwareUpdateErrorStarting.ErrorParsingPbz,
        )
        onSideload = {} // upstream re-fails identically: no state change at all
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        val state = assertIs<ForkFirmwareInstallState.Failed>(installer.stateValueFor("watch1"))
        assertContains(state.reason, "did not start")
        assertTrue(forkFilesLeft().isEmpty())
    }

    @Test
    fun timeoutWithTheTransferStillRunningLeavesTheFileAlone() = runTest {
        // The transfer wait giving up must not delete the zip mid-transfer:
        // PutBytes lazily re-opens it by path (the resources stream opens
        // only after the firmware stream completes), so deletion would kill
        // a slow-but-alive transfer partway. The stale sweep reclaims the
        // file in the next process instead.
        val pbz = singleSlotPbz()
        record(pbz)
        onSideload = { watchStates.value = FirmwareUpdateStatus.WaitingToStart(update()) }
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        assertEquals(1, forkFilesLeft().size, "file must outlive a transfer that is still running")
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateValueFor("watch1"))
    }

    @Test
    fun concurrentFirstAccessesShareOneStateFlowInstance() {
        // Real threads, not runTest: this pins the exact production race
        // (install coroutine on Dispatchers.Default vs a main-thread Compose
        // read doing the first access for a watch). Both must get the same
        // instance, or the UI collects a flow the install never writes to.
        val installer = installer(CoroutineScope(SupervisorJob()), serving(singleSlotPbz()))
        repeat(500) { i ->
            val key = "race-$i"
            val barrier = CyclicBarrier(2)
            val results = arrayOfNulls<Any>(2)
            val threads = (0..1).map { t ->
                Thread {
                    barrier.await()
                    results[t] = installer.stateFlowFor(key)
                }.apply { start() }
            }
            threads.forEach { it.join() }
            assertSame(results[0], results[1], "both threads must get the same flow for $key")
        }
    }

    @Test
    fun sequentialInstallsUseDistinctFilenames() = runTest {
        val pbz = singleSlotPbz()
        record(pbz)
        handoffSucceeds()
        val paths = mutableListOf<Path>()
        val previousOnSideload = onSideload
        onSideload = { path ->
            paths += path
            previousOnSideload(path)
        }
        val installer = installer(backgroundScope, serving(pbz))
        installer.runInstall(target(), update())
        watchStates.value = FirmwareUpdateStatus.NotInProgress.Idle()
        record(pbz)
        installer.runInstall(target(), update())
        assertEquals(2, paths.size)
        assertTrue(paths[0].name != paths[1].name, "each install must write to a fresh file")
    }
}
