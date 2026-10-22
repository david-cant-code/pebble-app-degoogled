package gravel.ring.sync

import co.touchlab.kermit.Logger
import coredevices.haversine.BluetoothFailureReason
import coredevices.haversine.CollectionGroup
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.DataDecodeException
import coredevices.haversine.KMPHaversineAdvertisement
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.HaversineFeatureUnavailable
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.SatelliteOps
import coredevices.haversine.SatelliteStatus
import coredevices.haversine.TransferStatus
import coredevices.haversine.fingerprintMatchesFailsafe
import coredevices.haversine.memoryNeedsServicing
import coredevices.haversine.memoryRecording
import coredevices.haversine.merged
import coredevices.haversine.parseAdvertisement
import gravel.ring.audio.DecodedAudio
import gravel.ring.normalizeRingId
import gravel.ring.protocol.MemoryMap
import gravel.ring.protocol.RingMissingDataException
import gravel.ring.protocol.RingProtocol
import gravel.ring.protocol.RingRequest
import gravel.ring.putU32le
import gravel.ring.records.ParsedCollection
import gravel.ring.records.buttonWords
import gravel.ring.records.collectionInstant
import gravel.ring.records.formatHardwareSerial
import gravel.ring.records.formatProgrammedSerial
import gravel.ring.records.isAudioGroup
import gravel.ring.records.parseCollection
import gravel.ring.records.parsePlatformVersion
import gravel.ring.ringIdsEqual
import gravel.ring.transport.RawAdvert
import gravel.ring.transport.RingLink
import gravel.ring.transport.RingTransport
import gravel.ring.u16le
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One ring session at a time. The stored index is the last index whose
 * group was finished or skipped. null means the window start. An open
 * multi-part group is not stored, so the next session reads those pages
 * again. `emit` completes only after the collector returns, and the index
 * is written after that.
 */
internal class RingEngine(
    private val transport: RingTransport,
    private val storage: CollectionIndexStorage,
    private val hacks: KMPHaversineHacksDelegate,
    private val debug: KMPHaversineDebugDelegate,
    private val pairedId: () -> String?,
    private val hwVersion: Pair<Int, Int>,
    private val active: Boolean,
) : SatelliteOps {
    private val log = Logger.withTag("GravelRing")
    private val satellites = HashMap<String, KMPHaversineSatellite>()
    private val sessionLock = Mutex()
    private val interviewed = HashSet<String>()
    private val lastPressCount = HashMap<String, Long>()
    private val _lastRing = MutableStateFlow<KMPHaversineSatellite?>(null)
    val lastRing: StateFlow<KMPHaversineSatellite?> = _lastRing

    private var scanScope: CoroutineScope? = null
    private var sessionJob: Job? = null
    private var deliveries: Channel<Delivered>? = null
    private var lastAdvert: RawAdvert? = null
    private var cachedEndLow: Int? = null
    private var forceConnect: Boolean = false

    fun satellite(id: String): KMPHaversineSatellite? {
        if (!active) return null
        val key = normalizeRingId(id)
        return satellites.getOrPut(key) { KMPHaversineSatellite(key, null, this) }
    }

    suspend fun awaitBluetoothReady() {
        if (!active) return
        transport.bluetoothReady.first { it }
    }

    fun startScanning(): Flow<SatelliteStatus> {
        if (!active) return emptyFlow()
        // A buffered send, and a rendezvous receive, both resume the sender
        // before the collector runs. The deferred completes after emit.
        return flow {
            coroutineScope {
                val outgoing = Channel<Delivered>(Channel.RENDEZVOUS)
                deliveries = outgoing
                scanScope = this
                val adverts = launch {
                    transport.advertisements().collect { onAdvert(this@coroutineScope, it) }
                }
                val radio = launch {
                    transport.bluetoothReady.collect { ready ->
                        if (!ready) {
                            sessionJob?.cancel()
                            outgoing.close()
                        }
                    }
                }
                try {
                    for (delivery in outgoing) {
                        emit(delivery.status)
                        delivery.done.complete(Unit)
                    }
                } finally {
                    adverts.cancel()
                    radio.cancel()
                    sessionJob?.cancel()
                    outgoing.close()
                    if (deliveries === outgoing) deliveries = null
                    if (scanScope === this) scanScope = null
                }
            }
        }
    }

    fun restartPreemptiveTransfer() {
        if (!active) return
        forceConnect = true
        val scope = scanScope ?: return
        val previous = sessionJob
        sessionJob = scope.launch {
            previous?.cancel()
            previous?.join()
            val advert = lastAdvert ?: return@launch
            sessionLock.withLock { runSession(advert) }
        }
    }

    suspend fun programUserId(): Result<Unit> =
        Result.failure(HaversineFeatureUnavailable("programSatelliteWithUserID"))

    override suspend fun eraseCollections(satellite: KMPHaversineSatellite) {
        if (!active) return
        sessionLock.withLock {
            val link = connectRetrying(satellite.id)
            try {
                softErase(RingProtocol(link), satellite)
            } finally {
                link.close()
            }
        }
    }

    private fun onAdvert(scope: CoroutineScope, raw: RawAdvert) {
        val paired = pairedId()?.let { normalizeRingId(it) } ?: return
        if (normalizeRingId(raw.id) != paired) return
        val payload = raw.manufacturerPayload ?: return
        val parsed = parseAdvertisement(
            payload,
            raw.rssi.toFloat(),
            Instant.fromEpochMilliseconds(raw.observedAtMillis),
        ) ?: return
        val satellite = satellite(raw.id) ?: return
        noteAdvert(satellite, raw, parsed)
        lastAdvert = raw
        if (sessionJob?.isActive == true) return
        if (!shouldConnect(parsed)) return
        forceConnect = false
        sessionJob = scope.launch {
            sessionLock.withLock { runSession(raw) }
        }
    }

    private fun shouldConnect(parsed: KMPHaversineAdvertisement): Boolean {
        if (forceConnect) return true
        val cached = cachedEndLow
        if (cached == null) return true
        if (cached != parsed.collectionCount) return true
        return parsed.needsServicing || parsed.recordingInProgress
    }

    private fun noteAdvert(
        satellite: KMPHaversineSatellite,
        raw: RawAdvert,
        parsed: KMPHaversineAdvertisement,
    ) {
        if (raw.name != null) satellite.name = raw.name
        satellite.lastAdvertisement = parsed
        satellite.replaceState(
            satellite.state.value.merged(
                isNearby = true,
                isInCollectionState = parsed.recordingInProgress,
                truncatedCollectionCount = parsed.collectionCount,
                countPresent = true,
                isInFailsafeMode = fingerprintMatchesFailsafe(parsed.fingerprint),
            ),
        )
        _lastRing.value = satellite
    }

    private suspend fun runSession(raw: RawAdvert) {
        forceConnect = false
        val payload = raw.manufacturerPayload ?: return
        val parsed = parseAdvertisement(
            payload,
            raw.rssi.toFloat(),
            Instant.fromEpochMilliseconds(raw.observedAtMillis),
        ) ?: return
        val satellite = satellite(raw.id) ?: return
        if (deliveries == null) return
        val link = try {
            connectRetrying(satellite.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deliver(SatelliteStatus.BluetoothFailure(satellite, connectFailureReason(e)))
            return
        }
        try {
            val protocol = RingProtocol(link)
            if (parsed.needsServicing && !writeClock(protocol, satellite)) return
            interview(protocol, satellite)
            if (fingerprintMatchesFailsafe(parsed.fingerprint)) {
                log.i { "failsafe image on ${satellite.id}, collection sync left unimplemented" }
                cachedEndLow = parsed.collectionCount
                return
            }
            if (hacks.shouldWipeCollectionsBeforeTransfer(satellite)) {
                softErase(protocol, satellite)
            }
            var cursor = storage.lastSuccessfulCollectionIndex.value
            val group = GroupBuffer()
            while (true) {
                val (start, end) = try {
                    readRange(protocol)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(satellite, cursor ?: -1, e)
                    return
                }
                for (segment in pendingIndices(start, end, cursor)) {
                    deliver(
                        SatelliteStatus.Transferring(
                            satellite,
                            TransferStatus.TransferStarted(satellite, segment.rollover, segment.range),
                        ),
                    )
                    for (index in segment.indices) {
                        deliver(
                            SatelliteStatus.Transferring(
                                satellite,
                                TransferStatus.TransferInProgress(satellite, index, group.startIndex),
                            ),
                        )
                        val page = try {
                            readPage(protocol, index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RingMissingDataException) {
                            skip(satellite, group, index, e)
                            cursor = index
                            continue
                        } catch (e: DataDecodeException) {
                            skip(satellite, group, index, e)
                            cursor = index
                            continue
                        } catch (e: Throwable) {
                            fail(satellite, index, e)
                            return
                        }
                        if (group.accept(index, page)) {
                            val abandoned = group.pages.last().index
                            skip(
                                satellite,
                                group,
                                abandoned,
                                DataDecodeException("collection group ended before a final part", null),
                            )
                            cursor = abandoned
                        }
                        group.add(index, page)
                        applyPageState(satellite, page)
                        val pages = group.parsed()
                        val audio = isGroupAudio(pages)
                        val sequence = sequenceFor(satellite.id, page)
                        val startIndex = group.startIndex
                        deliver(
                            SatelliteStatus.Transferring(
                                satellite,
                                TransferStatus.TransferTypeDetermined(
                                    satellite = satellite,
                                    isAudio = audio,
                                    buttonSequence = sequence,
                                    collectionStartIndex = startIndex,
                                    collectionIndex = index,
                                    final = page.finalPart,
                                    advertisementReceivedTimestamp = parsed.timestamp,
                                    lifetimeCollectionCount = page.lifetimeCount?.toUInt(),
                                ),
                            ),
                        )
                        if (!page.finalPart) {
                            cursor = index
                            continue
                        }
                        if (audio) {
                            val joined = joinAudio(pages)
                            deliver(
                                SatelliteStatus.Transferring(
                                    satellite,
                                    TransferStatus.TransferComplete(
                                        satellite = satellite,
                                        samples = joined?.samples ?: ShortArray(0),
                                        sampleRate = joined?.sampleRate ?: 0u,
                                        collectionStartCount = (startIndex ?: index).toLong(),
                                        collectionIndex = index,
                                        buttonSequence = sequence,
                                        buttonReleaseTimestamp = collectionInstant(page.unixSeconds),
                                        transferCompleteTimestamp = Clock.System.now(),
                                    ),
                                ),
                            )
                        }
                        storage.setLastSuccessfulCollectionIndex(index)
                        cursor = index
                        page.pressCount?.let { lastPressCount[satellite.id] = it }
                        group.clear()
                    }
                }
                val image = try {
                    protocol.read(RingRequest.Read(MemoryMap.ADVERT, 10u))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(satellite, cursor ?: start, e)
                    return
                }
                if (!parsed.needsServicing && memoryNeedsServicing(image)) {
                    if (!writeClock(protocol, satellite)) return
                }
                if (memoryRecording(image)) {
                    delay(45.milliseconds)
                    continue
                }
                cachedEndLow = end and 0xFF
                return
            }
        } finally {
            link.close()
        }
    }

    private suspend fun writeClock(
        protocol: RingProtocol,
        satellite: KMPHaversineSatellite,
    ): Boolean = try {
        val payload = ByteArray(4).also { it.putU32le(0, Clock.System.now().epochSeconds) }
        protocol.write(RingRequest.Write(MemoryMap.CLOCK, payload))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        fail(satellite, storage.lastSuccessfulCollectionIndex.value ?: -1, e)
        false
    }

    private suspend fun interview(protocol: RingProtocol, satellite: KMPHaversineSatellite) {
        if (interviewed.add(satellite.id)) {
            readOptional(protocol, MemoryMap.VERSIONS, 4u)?.let { bytes ->
                parsePlatformVersion(bytes)?.let { version ->
                    if (version.hardware != 0 to 0 && version.hardware != hwVersion) {
                        log.w {
                            "hardware ${version.hardware.first}.${version.hardware.second}, expected ${hwVersion.first}.${hwVersion.second}"
                        }
                    }
                    satellite.replaceState(satellite.state.value.merged(firmwareVersion = version.firmware))
                }
            }
            readOptional(protocol, MemoryMap.HARDWARE_SERIAL, 6u)?.let { bytes ->
                formatHardwareSerial(bytes)?.let { serial ->
                    satellite.replaceState(satellite.state.value.merged(serialNumber = serial))
                }
            }
            readOptional(protocol, MemoryMap.PROGRAMMED_SERIAL, 0u)?.let { bytes ->
                val serial = formatProgrammedSerial(bytes)
                satellite.replaceState(
                    satellite.state.value.merged(
                        programmedSerialNumber = serial,
                        programmedSerialPresent = true,
                    ),
                )
            }
        }
        if (debug.shouldReadRxRSSI(satellite)) {
            readOptional(protocol, MemoryMap.RX_RSSI, 1u)?.firstOrNull()?.let { byte ->
                val rssi = byte.toInt().toFloat()
                debug.handleRxRSSI(rssi, satellite)
                satellite.replaceState(
                    satellite.state.value.merged(rxRSSI = rssi, rxRSSIPresent = true),
                )
            }
        }
    }

    private suspend fun readOptional(protocol: RingProtocol, address: UInt, length: UInt): ByteArray? =
        try {
            protocol.read(RingRequest.Read(address, length))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.i { "interview 0x${address.toString(16)} skipped: ${e.message}" }
            null
        }

    /**
     * Stores end−1 and then asks the hacks delegate. A delegate that clears
     * the index is written back, because libindex calls the delegate again
     * after [eraseCollections] returns and a null index re-reads the window.
     */
    private suspend fun softErase(protocol: RingProtocol, satellite: KMPHaversineSatellite) {
        val (_, end) = readRange(protocol)
        val index = (end - 1) and 0xFFFF
        storage.setLastSuccessfulCollectionIndex(index)
        hacks.wipedCollectionsBeforeTransfer(satellite)
        if (storage.lastSuccessfulCollectionIndex.value != index) {
            storage.setLastSuccessfulCollectionIndex(index)
        }
    }

    private suspend fun readRange(protocol: RingProtocol): Pair<Int, Int> {
        val bytes = protocol.read(RingRequest.Read(MemoryMap.RANGE, 4u))
        if (bytes.size < 4) throw DataDecodeException("range shorter than 4", bytes)
        return bytes.u16le(0) to bytes.u16le(2)
    }

    private suspend fun readPage(protocol: RingProtocol, index: Int): ParsedCollection {
        val bytes = protocol.read(RingRequest.Read(MemoryMap.collection(index), 0u))
        return try {
            parseCollection(bytes)
        } catch (e: DataDecodeException) {
            throw DataDecodeException(e.message ?: "collection", bytes)
        }
    }

    private fun applyPageState(satellite: KMPHaversineSatellite, page: ParsedCollection) {
        page.batteryMillivolts?.let { mv ->
            satellite.replaceState(
                satellite.state.value.merged(batteryMillivolts = mv, batteryPresent = true),
            )
        }
        page.platformVersion?.let { parsePlatformVersion(it) }?.let { version ->
            if (version.firmware.isNotEmpty()) {
                satellite.replaceState(satellite.state.value.merged(firmwareVersion = version.firmware))
            }
        }
        val device = page.deviceId?.let { formatHardwareSerial(it) } ?: return
        val serial = satellite.state.value?.serialNumber.orEmpty()
        if (serial.isNotEmpty() && !ringIdsEqual(device, serial)) {
            log.w { "collection device id $device, serial $serial" }
        }
    }

    private fun sequenceFor(id: String, page: ParsedCollection): String? {
        val mask = page.pressBitmask ?: return null
        val count = page.pressCount ?: return null
        val previous = lastPressCount[id] ?: 0L
        return buttonWords(mask, count, previous).first
    }

    private fun isGroupAudio(pages: List<ParsedCollection>): Boolean {
        if (pages.size > 1 || pages.any { it.multiPart }) return true
        val audio = pages.firstOrNull()?.audio ?: return false
        return isAudioGroup(false, audio.samples.size, audio.sampleRate)
    }

    private fun joinAudio(pages: List<ParsedCollection>): DecodedAudio? {
        var samples = ShortArray(0)
        var rate = 0u
        var previous: ShortArray? = null
        for (page in pages) {
            val audio = page.audio ?: continue
            if (previous != null && previous.isNotEmpty() && audio.samples.isNotEmpty()) {
                val jump = kotlin.math.abs(audio.samples[0].toInt() - previous.last().toInt())
                if (jump > 4096) log.w { "sample jump $jump between collection parts" }
            }
            samples += audio.samples
            rate = audio.sampleRate
            previous = audio.samples
        }
        if (previous == null) return null
        return DecodedAudio(samples, rate)
    }

    /**
     * Returns after the scan collector has handled [status]. A collector
     * that throws, or a scan that has ended, cancels this call so the
     * index is not stored for an event nobody finished.
     */
    private suspend fun deliver(status: SatelliteStatus) {
        val channel = deliveries ?: throw CancellationException("scan ended")
        val done = CompletableDeferred<Unit>()
        channel.send(Delivered(status, done))
        done.await()
    }

    private suspend fun skip(
        satellite: KMPHaversineSatellite,
        group: GroupBuffer,
        index: Int,
        error: Throwable,
    ) {
        val first = group.pages.firstOrNull()?.index ?: index
        deliver(
            SatelliteStatus.Transferring(
                satellite,
                TransferStatus.IrrecoverableDataDetected(
                    satellite,
                    error,
                    CollectionGroup(group.startIndex, first..index),
                ),
            ),
        )
        storage.setLastSuccessfulCollectionIndex(index)
        group.clear()
    }

    private suspend fun fail(
        satellite: KMPHaversineSatellite,
        index: Int,
        error: Throwable,
    ) {
        log.i { "session failed at $index: ${error.message}" }
        deliver(
            SatelliteStatus.Transferring(
                satellite,
                TransferStatus.TransferFailed(satellite, index, error),
            ),
        )
    }

    private suspend fun connectRetrying(id: String): RingLink {
        var last: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return transport.connect(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (!shouldRetryConnect(e) || attempt == CONNECT_ATTEMPTS - 1) throw e
                delay(BACKOFF_MS[attempt].milliseconds)
            }
        }
        throw last ?: IllegalStateException("connect")
    }

    private class Delivered(
        val status: SatelliteStatus,
        val done: CompletableDeferred<Unit>,
    )

    private class GroupBuffer {
        val pages = ArrayList<Page>()
        val startIndex: Int? get() = pages.firstOrNull()?.start

        fun add(index: Int, page: ParsedCollection) {
            pages += Page(index, page.startIndex?.toInt() ?: index, page)
        }

        /**
         * True when [page] belongs to a different group than the one open.
         * The caller emits the open group as irrecoverable before adding.
         */
        fun accept(index: Int, page: ParsedCollection): Boolean {
            if (pages.isEmpty()) return false
            val start = page.startIndex?.toInt() ?: index
            return start != startIndex
        }

        fun parsed(): List<ParsedCollection> = pages.map { it.parsed }

        fun clear() {
            pages.clear()
        }
    }

    private class Page(
        val index: Int,
        val start: Int,
        val parsed: ParsedCollection,
    )

    companion object {
        private const val CONNECT_ATTEMPTS = 6
        private val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 4_000)

        fun inactive(storage: CollectionIndexStorage): RingEngine = RingEngine(
            transport = gravel.ring.transport.DeadTransport,
            storage = storage,
            hacks = NoHacks,
            debug = NoDebug,
            pairedId = { null },
            hwVersion = 11 to 0,
            active = false,
        )
    }
}

private object NoHacks : KMPHaversineHacksDelegate {
    override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = false
    override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = Unit
}

private object NoDebug : KMPHaversineDebugDelegate {
    override fun handleHaversineDebugInfo(info: coredevices.haversine.KMPHaversineDebugInfo) = Unit
    override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite) = false
    override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) = Unit
}
