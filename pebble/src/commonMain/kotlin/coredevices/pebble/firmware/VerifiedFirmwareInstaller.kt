package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifestWrapper
import io.rebble.libpebblecommon.util.Crc32Calculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Install-time progress of the fork's verified install pipeline. Only the
 * fork phases live here; once the file is handed to libpebble3 the upstream
 * FirmwareUpdateStatus drives the UI and this returns to Idle.
 */
sealed class ForkFirmwareInstallState {
    data object Idle : ForkFirmwareInstallState()
    data class Downloading(val progress: Float?) : ForkFirmwareInstallState()
    data object Verifying : ForkFirmwareInstallState()
    data object HandedOff : ForkFirmwareInstallState()
    data class Failed(val reason: String) : ForkFirmwareInstallState()

    val isActive: Boolean
        get() = this is Downloading || this is Verifying || this is HandedOff

    /** User-facing description, or null when there is nothing to show. */
    fun describe(): String? = when (this) {
        is Idle -> null
        is Downloading -> when {
            progress != null -> "Downloading PebbleOS update (${(progress * 100).toInt()}%)"
            else -> "Downloading PebbleOS update"
        }
        is Verifying -> "Verifying PebbleOS update"
        is HandedOff -> "Starting PebbleOS install"
        is Failed -> "PebbleOS install failed: $reason"
    }
}

/**
 * Downloads a firmware update itself, verifies it, and only then hands the
 * file to libpebble3's sideload entry point, which re-runs its own safety
 * checks and drives the transfer.
 *
 * Exists because the upstream install path (FirmwareDownloader inside
 * libpebble3's isolated Koin graph) performs no integrity checking at all:
 * no hash, no size, a shared fixed filename, and a 30 second timeout for the
 * whole download. There is no seam to fix that from the app side, so the
 * fork replaces the download step and enters libpebble3 through
 * sideloadFirmware(path) instead, keeping every upstream check intact.
 *
 * Verification, all fail closed (no expectation recorded, or any mismatch,
 * refuses the install and deletes the file):
 *  1. sha256(downloaded bytes) and exact size against what the update source
 *     declared at check time ([FirmwareArtifactExpectations]). Defends the
 *     download transport and CDN, not a compromised source account.
 *  2. Every manifest in the PBZ: hardware revision matches this watch,
 *     firmware type is "normal", version tag matches the release the checker
 *     selected (skipped when a manifest carries no tag).
 *  3. Pebble-CRC32 and size of the firmware and resources streams inside the
 *     zip against the manifest's own values. Upstream streams these to the
 *     watch without ever comparing them (its transfer CRC is computed over
 *     whatever bytes it reads), so a source-corrupt archive would otherwise
 *     install garbage.
 * The verified file stays in app-private storage between verification and
 * transfer; anything able to rewrite it there already controls the app.
 *
 * The install itself then runs exactly as upstream sideloads do, including
 * performSafetyChecks, dual-slot manifest selection, and progress states.
 */
class VerifiedFirmwareInstaller(
    private val httpClient: HttpClient,
    private val expectations: FirmwareArtifactExpectations,
    // Both seams are injected narrowly so tests can fake them without
    // constructing platform contexts or a full LibPebble.
    private val downloadDirectory: () -> Path,
    private val watchUpdateStates: (identifierKey: String) -> Flow<FirmwareUpdateStatus?>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = Logger.withTag("VerifiedFirmwareInstaller")
    private val stateMutex = Mutex()
    private val states = mutableMapOf<String, MutableStateFlow<ForkFirmwareInstallState>>()
    private val activeInstalls = mutableSetOf<String>()
    private var nextFileId = 0
    private var sweptStaleFiles = false

    fun stateFor(identifier: PebbleIdentifier): StateFlow<ForkFirmwareInstallState> =
        stateFlowFor(identifier.asString).asStateFlow()

    internal fun stateValueFor(identifierKey: String): ForkFirmwareInstallState =
        stateFlowFor(identifierKey).value

    fun install(device: CommonConnectedDevice, update: FirmwareUpdateCheckResult.FoundUpdate) {
        val target = InstallTarget(
            identifierKey = device.identifier.asString,
            platform = device.watchInfo.platform,
            currentUpdateState = { device.firmwareUpdateState },
            sideload = device::sideloadFirmware,
        )
        scope.launch { runInstall(target, update) }
    }

    /** Narrow view of a watch, so tests can drive the pipeline directly. */
    internal data class InstallTarget(
        val identifierKey: String,
        val platform: WatchHardwarePlatform,
        val currentUpdateState: () -> FirmwareUpdateStatus,
        val sideload: (Path) -> Unit,
    )

    internal suspend fun runInstall(
        target: InstallTarget,
        update: FirmwareUpdateCheckResult.FoundUpdate,
    ) {
        val state = stateFlowFor(target.identifierKey)
        stateMutex.withLock {
            if (target.identifierKey in activeInstalls) {
                logger.w { "Install already running for ${target.identifierKey}" }
                return
            }
            activeInstalls += target.identifierKey
        }
        var path: Path? = null
        try {
            state.value = ForkFirmwareInstallState.Downloading(progress = null)
            // Fail closed rather than fall back to the unverified upstream
            // download: expectations share the process lifetime of the
            // FoundUpdate itself, so a miss here is a bug, not bad luck.
            val expected = expectations.lookup(update.url)
                ?: throw InstallFailure("no integrity data recorded for this download")
            if (!update.url.startsWith("https://")) {
                throw InstallFailure("refusing non-https download")
            }
            if (target.currentUpdateState() !is FirmwareUpdateStatus.NotInProgress) {
                throw InstallFailure("an install is already in progress")
            }
            path = newDownloadPath(target.identifierKey)
            val actualSha256 = downloadTo(path, update.url, expected) { progress ->
                state.value = ForkFirmwareInstallState.Downloading(progress)
            }
            state.value = ForkFirmwareInstallState.Verifying
            if (actualSha256 != expected.sha256Hex) {
                throw InstallFailure("checksum mismatch")
            }
            verifyPbz(path, expected, target.platform)
            state.value = ForkFirmwareInstallState.HandedOff
            target.sideload(path)
            awaitHandoffOutcome(target, state)
            // Terminal either way by now (transfer ended, upstream error
            // shown, or watch gone): the temp file is no longer needed.
            deleteQuietly(path)
        } catch (e: CancellationException) {
            path?.let { deleteQuietly(it) }
            state.value = ForkFirmwareInstallState.Idle
            throw e
        } catch (e: InstallFailure) {
            logger.w { "Install failed for ${target.identifierKey}: ${e.reason}" }
            path?.let { deleteQuietly(it) }
            state.value = ForkFirmwareInstallState.Failed(e.reason)
        } catch (e: Exception) {
            // PbzFirmware/zip parsing throws unspecific exceptions; anything
            // unexpected still fails closed with the file removed.
            logger.e(e) { "Install failed for ${target.identifierKey}" }
            path?.let { deleteQuietly(it) }
            state.value = ForkFirmwareInstallState.Failed("could not verify firmware archive")
        } finally {
            stateMutex.withLock { activeInstalls -= target.identifierKey }
        }
    }

    /**
     * Streams the download to [path] while hashing, enforcing the expected
     * size as a hard cap, and failing on stalls instead of using upstream's
     * whole-download timeout (30s does not fit a multi-MB transfer on a slow
     * link). Returns the lowercase hex sha256 of the written bytes.
     */
    private suspend fun downloadTo(
        path: Path,
        url: String,
        expected: ExpectedFirmwareArtifact,
        onProgress: (Float?) -> Unit,
    ): String {
        val sizeCap = expected.sizeBytes ?: MAX_DOWNLOAD_BYTES
        val hashingSink = HashingSink.sha256(blackholeSink())
        val hashBuffer = Buffer()
        var received = 0L
        try {
            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw InstallFailure("download failed (${response.status.value})")
                }
                val total = expected.sizeBytes ?: response.contentLength()
                val channel = response.bodyAsChannel()
                SystemFileSystem.sink(path).buffered().use { fileSink ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                    while (true) {
                        val read = withTimeoutOrNull(READ_STALL_TIMEOUT) {
                            channel.readAvailable(buffer, 0, buffer.size)
                        } ?: throw InstallFailure("download stalled")
                        if (read == -1) break
                        if (read == 0) continue
                        received += read
                        if (received > sizeCap) {
                            throw InstallFailure("download larger than expected")
                        }
                        fileSink.write(buffer, startIndex = 0, endIndex = read)
                        hashBuffer.write(buffer, 0, read)
                        hashingSink.write(hashBuffer, hashBuffer.size)
                        onProgress(total?.let { (received.toDouble() / it).toFloat() })
                    }
                }
            }
        } catch (e: IOException) {
            throw InstallFailure("download failed (network error)")
        }
        if (expected.sizeBytes != null && received != expected.sizeBytes) {
            throw InstallFailure("download size mismatch")
        }
        return hashingSink.hash.hex()
    }

    /** Cross-checks every manifest in the archive; see the class KDoc. */
    internal fun verifyPbz(
        path: Path,
        expected: ExpectedFirmwareArtifact,
        platform: WatchHardwarePlatform,
    ) {
        val manifests = PbzFirmware(path).manifests
        if (manifests.isEmpty()) {
            throw InstallFailure("firmware archive contains no manifest")
        }
        manifests.forEach { wrapper ->
            val firmware = wrapper.manifest.firmware
            if (firmware.type != "normal") {
                throw InstallFailure("unexpected firmware type '${firmware.type}'")
            }
            if (firmware.hwRev != platform) {
                throw InstallFailure(
                    "firmware is for '${firmware.hwRev.revision}', not this watch",
                )
            }
            val manifestTag = firmware.versionTag
            if (manifestTag != null) {
                if (!versionTagMatches(expected.versionTag, manifestTag)) {
                    throw InstallFailure(
                        "firmware version '$manifestTag' does not match release '${expected.versionTag}'",
                    )
                }
            } else {
                // Tag equality is a selection-bug guard on top of the byte
                // hash, so a tagless manifest downgrades gracefully instead
                // of breaking sources that never wrote one.
                logger.w { "Manifest has no versionTag; skipping tag cross-check" }
            }
            checkStreamCrc(wrapper, isResources = false)
            if (wrapper.manifest.resources != null) {
                checkStreamCrc(wrapper, isResources = true)
            }
        }
    }

    /**
     * Pebble-CRC32 and byte count of an inner archive stream against the
     * manifest's declared values, using the same CRC implementation the
     * transfer layer uses.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun checkStreamCrc(wrapper: PbzManifestWrapper, isResources: Boolean) {
        val (label, declaredCrc, declaredSize) = if (isResources) {
            val resources = checkNotNull(wrapper.manifest.resources)
            Triple("resources", resources.crc, resources.size)
        } else {
            Triple("firmware", wrapper.manifest.firmware.crc, wrapper.manifest.firmware.size)
        }
        val source = (if (isResources) checkNotNull(wrapper.getResources()) else wrapper.getFirmware()).buffered()
        val crc = Crc32Calculator()
        var totalBytes = 0L
        source.use {
            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
            while (true) {
                val read = it.readAtMostTo(buffer, 0, buffer.size)
                if (read == -1) break
                totalBytes += read
                crc.addBytes(buffer.asUByteArray().copyOfRange(0, read))
            }
        }
        if (totalBytes != declaredSize) {
            throw InstallFailure("$label size does not match its manifest")
        }
        if (crc.finalize() != declaredCrc.toUInt()) {
            throw InstallFailure("$label failed its manifest CRC check")
        }
    }

    /**
     * The sideload entry point rejects silently when another update holds
     * its mutex and reports parse failures through the upstream state flow,
     * so observe that flow: fail if nothing starts, and once the transfer is
     * running let the upstream states drive the UI (fork state goes Idle).
     * The temp file must outlive the whole transfer because PutBytes lazily
     * re-reads the zip while streaming.
     */
    private suspend fun awaitHandoffOutcome(
        target: InstallTarget,
        state: MutableStateFlow<ForkFirmwareInstallState>,
    ) {
        val updateStates = watchUpdateStates(target.identifierKey)
        val started = withTimeoutOrNull(HANDOFF_START_TIMEOUT) {
            updateStates.first {
                it is FirmwareUpdateStatus.Active || it is FirmwareUpdateStatus.NotInProgress.ErrorStarting
            }
        }
        when (started) {
            null -> throw InstallFailure("install did not start")
            is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> {
                // The upstream error state is already user-visible; going
                // Idle here avoids rendering the failure twice.
                logger.w { "libpebble3 rejected the sideload: ${started.error}" }
                state.value = ForkFirmwareInstallState.Idle
            }
            else -> {
                state.value = ForkFirmwareInstallState.Idle
                withTimeoutOrNull(TRANSFER_TIMEOUT) {
                    updateStates.first {
                        // null: the watch vanished from the device list
                        // (disconnect or post-update reboot).
                        it == null ||
                            it is FirmwareUpdateStatus.NotInProgress ||
                            it is FirmwareUpdateStatus.WaitingForReboot
                    }
                }
            }
        }
    }

    private fun stateFlowFor(identifierKey: String): MutableStateFlow<ForkFirmwareInstallState> {
        // Plain synchronized access is not available in common code; the map
        // is only touched from install paths and Compose reads, both of
        // which tolerate a racy first-creation (worst case an extra flow
        // instance is briefly created and dropped).
        return states.getOrPut(identifierKey) { MutableStateFlow(ForkFirmwareInstallState.Idle) }
    }

    private suspend fun newDownloadPath(identifierKey: String): Path {
        val dir = downloadDirectory()
        stateMutex.withLock {
            if (!sweptStaleFiles) {
                sweptStaleFiles = true
                sweepStaleDownloads(dir)
            }
            val safeKey = identifierKey.filter { it.isLetterOrDigit() }
            return Path(dir, "$FILE_PREFIX$safeKey-${nextFileId++}.pbz")
        }
    }

    /**
     * Downloads from previous processes (crash, force stop) are dead weight:
     * expectations do not survive the process, so they can never be
     * installed. One sweep per process, before the first download.
     */
    private fun sweepStaleDownloads(dir: Path) {
        try {
            SystemFileSystem.list(dir)
                .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".pbz") }
                .forEach { deleteQuietly(it) }
        } catch (e: IOException) {
            logger.w(e) { "Couldn't sweep stale firmware downloads" }
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (e: IOException) {
            logger.w(e) { "Couldn't delete $path" }
        }
    }

    private class InstallFailure(val reason: String) : Exception(reason)

    private fun versionTagMatches(expectedTag: String, manifestTag: String): Boolean {
        val expected = ReleaseTagVersion.from(expectedTag)
        val manifest = ReleaseTagVersion.from(manifestTag)
        // Numeric comparison tolerates cosmetic differences like a suffix;
        // exact string equality is the fallback when either side is exotic.
        return if (expected != null && manifest != null) {
            expected.compareTo(manifest) == 0
        } else {
            expectedTag == manifestTag
        }
    }

    companion object {
        private const val FILE_PREFIX = "fork-fw-"
        private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

        // Only applies when the source declared no size (cohorts): far above
        // any real PBZ (about 2 MB today) but still bounds a runaway stream.
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private val READ_STALL_TIMEOUT = 30.seconds
        private val HANDOFF_START_TIMEOUT = 30.seconds
        private val TRANSFER_TIMEOUT = 30.minutes
    }
}
