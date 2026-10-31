package com.anopticlabs.gravel.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.pipeline.CaptureIngest
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.ring.link.RING_ADVERTISED_SERVICE
import com.anopticlabs.gravel.ring.link.withRingConnection
import com.anopticlabs.gravel.ring.protocol.RING_COMPANY_ID
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.parseRingAdvertisement
import com.anopticlabs.gravel.ring.sync.RingSession
import com.anopticlabs.gravel.ui.NotebookTab
import com.anopticlabs.gravel.ui.RingSyncStatus
import com.juul.kable.Scanner
import coredevices.libindex.IndexDevices
import coredevices.libindex.LibIndex
import coredevices.libindex.device.DiscoveredIndexDevice
import coredevices.libindex.device.IndexDevice
import coredevices.libindex.device.IndexIdentifier
import coredevices.libindex.device.IndexImage
import coredevices.libindex.device.IndexPairingResult
import coredevices.libindex.device.IndexPairingState
import coredevices.libindex.device.KnownIndexDevice
import coredevices.libindex.device.PairingRequestResult
import coredevices.ring.database.Preferences
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.util.asFlow
import java.io.File
import java.io.IOException
import java.text.DateFormat
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

private const val RING_NAME = "Index 01"
private const val MAX_HEARD_NAME_CODE_POINTS = 32L

/** A search lists at most this many rings, so a device cycling through addresses cannot flood the list. */
private const val MAX_HEARD_RINGS = 16

/** Not heard for this long after a sync or a failed one, a ring's entry says when it was last seen. */
private const val NOT_SEEN_AFTER_MILLIS = 30 * 60 * 1000L

private val HIDDEN_CHARACTER_TYPES = setOf(
    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
).map(Byte::toInt)

/** A ring a search heard. */
internal data class HeardRing(val address: String, val name: String, val rssi: Int, val advertisement: RingAdvertisement)

/** What a search heard at [address], or null when it is not a ring's advertisement. */
internal fun heardRing(address: String, name: String?, rssi: Int, manufacturerData: ByteArray?): HeardRing? {
    if (!RING_ADDRESS.matches(address)) return null
    val advertisement = manufacturerData?.let(::parseRingAdvertisement) ?: return null
    return HeardRing(address, shownRingName(name), rssi, advertisement)
}

/** An advertised [name] as the Devices tab shows it: no control, format or line-breaking characters, at most 32 code points. */
internal fun shownRingName(name: String?): String {
    val kept = name.orEmpty().codePoints().filter { Character.getType(it) !in HIDDEN_CHARACTER_TYPES }
        .limit(MAX_HEARD_NAME_CODE_POINTS).toArray()
    return String(kept, 0, kept.size).takeIf { it.isNotBlank() } ?: RING_NAME
}

/** The ring paired with Gravel's Pair button, by address, in the no-backup directory: a bond stays on its phone. */
class PairedRingFile(dir: File) {
    private val file = DeviceStateFile(dir, "ring_paired", maxChars = 17)
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    /** Reads the saved ring into [address]; anything but an address reads as none. */
    fun load() {
        _address.value = file.read()?.takeIf { RING_ADDRESS.matches(it) }
    }

    fun save(address: String?): Boolean {
        require(address == null || RING_ADDRESS.matches(address)) { "not a Bluetooth address" }
        val saved = if (address == null) file.clear() else file.write(address)
        if (saved) _address.value = address
        return saved
    }
}

internal enum class RingBond { NONE, BONDING, BONDED }

/** A bond change Android reported, with Android's reason when a bond attempt ended without a bond. */
internal data class RingBondChange(val address: String, val bond: RingBond, val unbondReason: Int? = null)

/** Android's bonds, behind an interface so pairing runs in host tests. */
internal interface RingBonds {
    val changes: SharedFlow<RingBondChange>

    /** Bluetooth turning on, after which bonds can be read again. */
    val bluetoothOn: SharedFlow<Unit>

    /** [address]'s bond as read now; null while it cannot be read (Bluetooth off or no permission). */
    fun bond(address: String): RingBond?

    fun create(address: String): Boolean
}

internal sealed interface BondOutcome {
    data object Bonded : BondOutcome
    data object NotStarted : BondOutcome
    data object TimedOut : BondOutcome
    data class Ended(val unbondReason: Int?) : BondOutcome
}

/**
 * Bonds with [address], or confirms it is bonded already. A reported end without a bond ends the wait once a fresh
 * read agrees; the bond is also read every [pollInterval], so a bond made before the wait began is not missed.
 */
internal suspend fun bondRing(
    bonds: RingBonds,
    address: String,
    timeout: Duration = 60.seconds,
    pollInterval: Duration = 1.seconds,
): BondOutcome = coroutineScope {
    if (bonds.bond(address) == RingBond.BONDED) return@coroutineScope BondOutcome.Bonded
    val changes = Channel<RingBondChange>(Channel.CONFLATED)
    val listening = launch(start = CoroutineStart.UNDISPATCHED) {
        bonds.changes.filter { it.address == address }.collect { changes.trySend(it) }
    }
    try {
        if (!bonds.create(address)) return@coroutineScope BondOutcome.NotStarted
        withTimeoutOrNull(timeout) {
            var ended: RingBondChange? = null
            var outcome: BondOutcome? = null
            while (outcome == null) {
                withTimeoutOrNull(pollInterval) { changes.receive() }?.let { if (it.bond == RingBond.NONE) ended = it }
                val now = bonds.bond(address)
                outcome = when {
                    now == RingBond.BONDED -> BondOutcome.Bonded
                    now == RingBond.NONE && ended != null -> BondOutcome.Ended(ended?.unbondReason)
                    else -> null
                }
            }
            outcome
        } ?: BondOutcome.TimedOut
    } finally {
        listening.cancel()
    }
}

/** Android's unbond reasons read as upstream/master's `IndexPairing.bond` reads them, failed authentication as a ring bonded elsewhere. */
internal fun bondFailure(unbondReason: Int?): PairingRequestResult = when (unbondReason) {
    LEConstants.UNBOND_REASON_AUTH_FAILED, LEConstants.UNBOND_REASON_AUTH_REJECTED -> PairingRequestResult.RingAlreadyPaired
    LEConstants.UNBOND_REASON_AUTH_CANCELLED -> PairingRequestResult.UserRejected
    else -> PairingRequestResult.Error(IllegalStateException("bonding ended without a bond, reason $unbondReason"))
}

internal class AndroidRingBonds(private val context: Context) : RingBonds {
    private val _changes = MutableSharedFlow<RingBondChange>(extraBufferCapacity = 16)
    override val changes: SharedFlow<RingBondChange> = _changes.asSharedFlow()
    private val _bluetoothOn = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val bluetoothOn: SharedFlow<Unit> = _bluetoothOn.asSharedFlow()

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Feeds [changes] and [bluetoothOn] until [scope] ends. The receiver is exported because Android's Bluetooth
     * process sends both broadcasts; both are protected ones (android16-release, frameworks/base
     * `core/res/AndroidManifest.xml`, refused from other senders in `BroadcastController.broadcastIntentLockedTraced`),
     * and what they report is acted on only when a fresh read of the bond agrees.
     */
    fun watch(scope: CoroutineScope) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        scope.launch {
            filter.asFlow(context, exported = true).collect { intent ->
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) _bluetoothOn.emit(Unit)
                } else {
                    intent.bondChange()?.let { _changes.emit(it) }
                }
            }
        }
    }

    override fun bond(address: String): RingBond? = try {
        val adapter = adapter?.takeIf { it.isEnabled }
        val state = adapter?.getRemoteDevice(address)?.bondState
        when {
            adapter == null -> null
            state == BluetoothDevice.BOND_BONDED || adapter.bondedDevices.orEmpty().any { it.address == address } -> RingBond.BONDED
            else -> state?.let(::ringBond)
        }
    } catch (e: SecurityException) {
        null
    }

    override fun create(address: String): Boolean = try {
        adapter?.getRemoteDevice(address)?.createBond() == true
    } catch (e: SecurityException) {
        logger.w(e) { "no permission to bond" }
        false
    }

    // The unbond reason is the extra libpebble3's getBluetoothDevicePairEvents reads.
    private fun Intent.bondChange(): RingBondChange? {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return null
        val bond = ringBond(getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) ?: return null
        val reason = getIntExtra("android.bluetooth.device.extra.REASON", -1).takeIf { it != -1 }
        return RingBondChange(device.address, bond, reason)
    }

    private fun ringBond(state: Int): RingBond? = when (state) {
        BluetoothDevice.BOND_NONE -> RingBond.NONE
        BluetoothDevice.BOND_BONDING -> RingBond.BONDING
        BluetoothDevice.BOND_BONDED -> RingBond.BONDED
        else -> null
    }

    private companion object {
        val logger = Logger.withTag("AndroidRingBonds")
    }
}

/** The line a paired ring's Devices entry shows; [time] writes a wall-clock time. */
internal fun ringStatusLine(
    state: RingSyncState,
    searchFailed: Boolean,
    lastHeardMillis: Long?,
    nowMillis: Long,
    time: (Long) -> String,
): String = when {
    state == RingSyncState.Syncing || state == RingSyncState.PositionLost || state == RingSyncState.Stopped -> stateLine(state, time)
    searchFailed -> "Can't search for the ring right now"
    lastHeardMillis != null && nowMillis - lastHeardMillis > NOT_SEEN_AFTER_MILLIS &&
        (state is RingSyncState.Synced || state is RingSyncState.Failed) -> "Not seen since ${time(lastHeardMillis)}"
    state == RingSyncState.Listening && lastHeardMillis != null -> "Last seen at ${time(lastHeardMillis)}"
    else -> stateLine(state, time)
}

private fun stateLine(state: RingSyncState, time: (Long) -> String): String = when (state) {
    RingSyncState.Listening -> "Waiting to hear from the ring"
    RingSyncState.Syncing -> "Syncing"
    is RingSyncState.Synced -> "Synced at ${time(state.atMillis)}, " + when (state.added) {
        0 -> "nothing new"
        1 -> "1 recording added"
        else -> "${state.added} recordings added"
    }
    is RingSyncState.Waiting -> when (state.reason) {
        RingWait.NOTEBOOK_OFF -> "Recordings stay on the ring while the Notebook is off"
        RingWait.TOO_MANY_WAITING ->
            "Recordings stay on the ring while $MAX_RING_RECORDINGS_QUEUED ring recordings wait in the Notebook, flagged ones included"
        RingWait.HOURLY_LIMIT -> "Recordings stay on the ring for now: $MAX_RING_RECORDINGS_PER_HOUR came in the past hour"
        RingWait.NOT_KEPT -> "Recordings stay on the ring: the Notebook could not keep the last one"
    }
    is RingSyncState.Failed -> "Sync failed at ${time(state.atMillis)}, trying again"
    RingSyncState.PairAgain -> "Forget this ring in Android's Bluetooth settings, then pair it again"
    RingSyncState.PositionLost -> "Syncing stopped: Gravel lost track of which recordings it imported"
    RingSyncState.Stopped -> "Not syncing: Gravel's background service isn't running"
}

/**
 * Gravel's [LibIndex], on `:ring`: what upstream's Devices tab shows and does for rings. A search lists the rings
 * it hears; Pair bonds with one and keeps it as the paired ring, which [runner] then syncs; the paired ring is
 * dropped when a read of Android's bonds finds it gone, and when the user removes it. It never presents an
 * `InterviewedIndexDevice`, the type that opens upstream's Index feature.
 */
class RingClientLibIndex internal constructor(
    private val pairedRing: PairedRingFile,
    private val bonds: RingBonds,
    private val runner: RingSyncRunner,
    private val hearNearby: () -> Flow<HeardRing>,
    private val scope: CoroutineScope,
    private val startWatching: (CoroutineScope) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val time: (Long) -> String = { it.toString() },
    private val searchTime: Duration = 30.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibIndex {
    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val heard = MutableStateFlow<Map<String, Pair<HeardRing, IndexPairingState>>>(emptyMap())
    private val _rings = MutableStateFlow<List<IndexDevice>>(emptyList())
    override val rings: IndexDevices = _rings.asStateFlow()

    private var search: Job? = null
    private var paired: PairedRing? = null
    private val pairingLock = Mutex()

    /** Loads the paired ring and starts following it and Android's bonds; called once at app start. */
    override fun init(bluetoothPermissionChanged: Flow<Boolean>) {
        scope.launch {
            withContext(io) { pairedRing.load() }
            startWatching(this)
            launch { combine(pairedRing.address, heard) { _, _ -> }.collect { publish() } }
            launch { bonds.changes.collect { dropIfUnbonded(it.address) } }
            launch { bonds.bluetoothOn.collect { pairedRing.address.value?.let { dropIfUnbonded(it) } } }
            bluetoothPermissionChanged.collect { granted -> if (granted) pairedRing.address.value?.let { dropIfUnbonded(it) } }
        }
    }

    override fun startScan() {
        if (search?.isActive == true) return
        heard.update { rings -> rings.filterValues { (_, state) -> state == IndexPairingState.Pairing } }
        search = scope.launch {
            _isScanning.value = true
            try {
                withTimeoutOrNull(searchTime) {
                    hearNearby().collect { ring -> heard.update { it.withHeard(ring) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "searching for rings failed" }
            } finally {
                _isScanning.value = false
            }
        }
    }

    override fun stopScan() {
        search?.cancel()
        heard.update { rings -> rings.filterValues { (_, state) -> state == IndexPairingState.Pairing } }
    }

    /** Never called while `enableIndex` stays off (PebbleBackgroundManager); Gravel shows no such warning. */
    override fun warnIfNoCompanionAssociations() {}

    private fun Map<String, Pair<HeardRing, IndexPairingState>>.withHeard(ring: HeardRing) = when {
        ring.address in this -> this + (ring.address to (ring to getValue(ring.address).second))
        size >= MAX_HEARD_RINGS -> this
        else -> this + (ring.address to (ring to IndexPairingState.NotPaired))
    }

    private suspend fun dropIfUnbonded(address: String) {
        if (address != pairedRing.address.value || bonds.bond(address) != RingBond.NONE) return
        logger.i { "Android has no bond with $address; it is no longer the paired ring" }
        withContext(io) { pairedRing.save(null) }
    }

    // In the client's scope: leaving the screen does not cancel a bond the user is confirming.
    private suspend fun pair(ring: HeardRing): IndexPairingResult = scope.async { pairing(ring) }.await()

    private suspend fun pairing(ring: HeardRing): IndexPairingResult {
        if (!pairingLock.tryLock()) {
            logger.w { "pairing with ${ring.address} refused: a pairing is running" }
            return IndexPairingResult.PairingFailure(PairingRequestResult.Error(IllegalStateException("a pairing is running")))
        }
        try {
            val refused = when {
                !ring.advertisement.pairable -> "it runs its failsafe or factory-test image"
                pairedRing.address.value != null -> "a ring is paired already"
                else -> null
            }
            if (refused != null) return failure(ring, PairingRequestResult.Error(IllegalStateException(refused)))
            setPairing(ring, IndexPairingState.Pairing)
            search?.cancel()
            val outcome = try {
                bondRing(bonds, ring.address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "bonding with ${ring.address} failed" }
                BondOutcome.NotStarted
            }
            val cause = when (outcome) {
                BondOutcome.Bonded -> null
                BondOutcome.NotStarted -> PairingRequestResult.CreateBondFailed
                BondOutcome.TimedOut -> PairingRequestResult.Error(IllegalStateException("no bond within the time allowed"))
                is BondOutcome.Ended -> bondFailure(outcome.unbondReason)
            }
            if (cause != null) return failure(ring, cause)
            if (!withContext(io) { pairedRing.save(ring.address) }) {
                return failure(ring, PairingRequestResult.Error(IOException("the paired ring was not saved")))
            }
            logger.i { "paired with ${ring.address}" }
            heard.value = emptyMap()
            return IndexPairingResult.Success
        } finally {
            pairingLock.unlock()
        }
    }

    private fun failure(ring: HeardRing, cause: PairingRequestResult): IndexPairingResult {
        val result = IndexPairingResult.PairingFailure(cause)
        val why = (cause as? PairingRequestResult.Error)?.cause?.message ?: cause::class.simpleName
        logger.w { "pairing with ${ring.address} failed: $why" }
        setPairing(ring, IndexPairingState.Error(result))
        return result
    }

    private fun setPairing(ring: HeardRing, state: IndexPairingState) {
        heard.update { it + (ring.address to (ring to state)) }
    }

    private fun publish() {
        val address = pairedRing.address.value
        val known = address?.let { a -> paired?.takeIf { it.address == a } ?: PairedRing(a).also { paired = it } }
        _rings.value = listOfNotNull(known) +
            heard.value.values.filter { (ring, _) -> ring.address != address }.map { (ring, state) -> DiscoveredRing(ring, state) }
    }

    private inner class DiscoveredRing(private val ring: HeardRing, override val pairingState: IndexPairingState) : DiscoveredIndexDevice {
        override val identifier = IndexIdentifier.fromPlatformAddress(ring.address)
        override val name = ring.name
        override val rssi = ring.rssi
        override val currentImage = when {
            ring.advertisement.pairable -> IndexImage.Primary
            ring.advertisement.factoryTestImage -> IndexImage.ProductionTest
            else -> IndexImage.Failsafe
        }

        override suspend fun pair(): IndexPairingResult = this@RingClientLibIndex.pair(ring)
    }

    private inner class PairedRing(val address: String) : KnownIndexDevice, RingSyncStatus {
        override val identifier = IndexIdentifier.fromPlatformAddress(address)
        override val name = RING_NAME

        override val statusLine: StateFlow<String> by lazy {
            combine(runner.state, runner.searchFailed, runner.lastHeard, minuteTicks()) { state, failed, heard, _ -> line(state, failed, heard) }
                .stateIn(scope, SharingStarted.WhileSubscribed(5_000), line(runner.state.value, runner.searchFailed.value, runner.lastHeard.value))
        }

        override val canImportAgain: StateFlow<Boolean>
            get() = runner.positionLost

        override suspend fun importAgain() = runner.importAgain()

        override fun remove() {
            scope.launch(io) {
                if (pairedRing.address.value == address) pairedRing.save(null)
            }
        }

        private fun line(state: RingSyncState, failed: Boolean, heard: Long?) = ringStatusLine(state, failed, heard, clock(), time)
    }

    private companion object {
        val logger = Logger.withTag("RingClientLibIndex")

        fun minuteTicks(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(1.minutes)
            }
        }
    }
}

/** Marks on the clock that keeps counting in deep sleep (android16-release, frameworks/base `SystemClock` class doc). */
private object ElapsedRealtime : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}

/** The rings a search on the ring's service hears nearby. */
private fun ringsNearby(): Flow<HeardRing> =
    Scanner {
        preConflate = true
        filters { match { services = listOf(RING_ADVERTISED_SERVICE) } }
    }.advertisements
        .mapNotNull { heardRing(it.identifier, it.name, it.rssi, it.manufacturerData(RING_COMPANY_ID)) }

/** The advertisements of the ring at [address]. */
private fun ringAdvertisements(address: String): Flow<RingAdvertisement> =
    Scanner {
        preConflate = true
        filters {
            match {
                services = listOf(RING_ADVERTISED_SERVICE)
                this.address = address
            }
        }
    }.advertisements.mapNotNull { advertisement -> advertisement.manufacturerData(RING_COMPANY_ID)?.let(::parseRingAdvertisement) }

val ringClientModule = module {
    single { PairedRingFile(androidContext().noBackupFilesDir) }
    single { AndroidRingBonds(androidContext()) }
    single {
        NotebookRingSink(
            notebookOn = {
                val tab = get<NotebookTab>()
                notebookTakesWatchRecordings(tab.shown.value, { tab.setupGuide.first { it.read }.done }, tab::recordingSupported)
            },
            ringRecordingsQueued = {
                val queue = get<QueueStore>()
                withContext(Dispatchers.IO) { queue.readQueue().count(QueuedCapture::fromRing) } + queue.retainedRingCount()
            },
            ingest = { wav, at -> get<CaptureIngest>().ingest(wav, "wav", capturedAtMillis = at, fromRing = true) },
        )
    }
    single {
        val context = androidContext()
        val sink = get<NotebookRingSink>()
        val positions = { address: String -> RingSyncFiles.of(context.noBackupFilesDir, address) }
        RingSyncRunner(
            paired = get<PairedRingFile>().address,
            hear = ::ringAdvertisements,
            sync = { address, advertisement ->
                val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) { "no Bluetooth" }
                withRingConnection(adapter.getRemoteDevice(address)) { connection ->
                    RingSession(connection, positions(address), sink, { System.currentTimeMillis() / 1000 }, MAX_CAPTURE_MILLIS.toLong())
                        .run(advertisement)
                }
            },
            lastCount = { address -> positions(address).lastCount() },
            positionReadable = { address ->
                try {
                    positions(address).nextIndex()
                    true
                } catch (e: RingPositionUnreadableException) {
                    false
                }
            },
            waitReason = sink::waitReason,
            lastWait = { sink.lastWait },
            imported = { sink.imported },
            startOver = { address -> positions(address).startOver() },
            marks = ElapsedRealtime,
        )
    }
    single {
        val bonds = get<AndroidRingBonds>()
        RingClientLibIndex(
            pairedRing = get(),
            bonds = bonds,
            runner = get(),
            hearNearby = ::ringsNearby,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            startWatching = bonds::watch,
            time = { DateUtils.formatSameDayTime(it, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString() },
        )
    } bind LibIndex::class
    single { Preferences(get<PairedRingFile>().address) }
}
