@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package gravel.ring.sync

import coredevices.haversine.BluetoothFailureReason
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.SatelliteStatus
import coredevices.haversine.TransferStatus
import gravel.ring.FakeRing
import gravel.ring.clockRecords
import gravel.ring.collectionOf
import gravel.ring.groupRecord
import gravel.ring.pcmRecord
import gravel.ring.tapRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SyncEngineTest {
    @Test
    fun pendingWindowSplitsAtTheWrapAndTreatsAGapAsRollover() {
        val wrapped = pendingIndices(65535, 1, null)
        assertEquals(2, wrapped.size)
        assertEquals(65535..65535, wrapped[0].range)
        assertEquals(false, wrapped[0].rollover)
        assertEquals(0..0, wrapped[1].range)
        assertEquals(true, wrapped[1].rollover)
        assertTrue(pendingIndices(0, 5, 4).isEmpty())
        val gap = pendingIndices(0, 3, 10)
        assertEquals(true, gap.single().rollover)
        assertEquals(listOf(0, 1, 2), gap.single().indices)
        assertEquals(listOf(2, 3, 4), pendingIndices(0, 5, 1).single().indices)
        assertEquals(false, pendingIndices(0, 5, null).single().rollover)
    }

    @Test
    fun softEraseSkipsFactoryDataAndRestoresAClearedIndex() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 5
        fake.collections[0] = tap(0)
        val storage = MemIndex()
        val hacks = TestHacks(storage, wipe = true, nullsIndex = true)
        val engine = engine(fake, storage, hacks)
        val events = collect(engine)
        fake.emit(count = 5)
        advanceUntilIdle()
        assertEquals(4, storage.value)
        assertEquals(1, hacks.wiped)
        assertTrue(events.none { it is TransferStatus.TransferStarted })
        events.cancel()
    }

    @Test
    fun eraseCollectionsRestoresTheIndexWhenTheDelegateClearsIt() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 5
        val storage = MemIndex()
        val hacks = TestHacks(storage, nullsIndex = true)
        val engine = engine(fake, storage, hacks)
        val satellite = engine.satellite("RING") ?: fail("missing satellite")
        satellite.eraseCollections()
        assertEquals(4, storage.value)
        assertEquals(1, hacks.wiped)
        assertEquals(1, fake.politeDisconnects)
    }

    @Test
    fun multiPartGroupIsReReadOnTheNextSession() = runTest {
        val fake = FakeRing()
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val events = collect(engine)
        fake.rangeEnd = 1
        fake.collections[0] = collectionOf(groupRecord(0, multi = true, final = false), pcmRecord(8_000, 1, 2))
        fake.emit(count = 1)
        advanceUntilIdle()
        assertNull(storage.value)
        assertTrue(events.snapshot().none { it is TransferStatus.TransferComplete })

        fake.rangeEnd = 2
        fake.collections[1] = collectionOf(groupRecord(0, multi = true, final = true), pcmRecord(8_000, 3, 4))
        fake.emit(count = 2)
        advanceUntilIdle()
        assertEquals(1, storage.value)
        val complete = events.snapshot().filterIsInstance<TransferStatus.TransferComplete>().single()
        assertEquals(4, complete.samples.size)
        assertEquals(0L, complete.collectionStartCount)
        assertEquals(1, complete.collectionIndex)
        events.cancel()
    }

    @Test
    fun wrapEmitsASecondStartedEventWithRollover() = runTest {
        val fake = FakeRing()
        fake.rangeStart = 65535
        fake.rangeEnd = 1
        fake.collections[65535] = tap(1)
        fake.collections[0] = tap(2)
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val events = collect(engine)
        fake.emit(count = 1)
        advanceUntilIdle()
        val started = events.snapshot().filterIsInstance<TransferStatus.TransferStarted>()
        assertEquals(2, started.size)
        assertEquals(false, started[0].rollover)
        assertEquals(true, started[1].rollover)
        assertEquals(0, storage.value)
        events.cancel()
    }

    @Test
    fun failedReadDoesNotAdvanceAndACorruptPageIsSkipped() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 1
        fake.failStatus[0] = 0x01
        fake.collections[0] = tap(1)
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val events = collect(engine)
        fake.emit(count = 1)
        advanceUntilIdle()
        assertNull(storage.value)
        assertTrue(events.snapshot().any { it is TransferStatus.TransferFailed })
        events.cancel()

        val fake3 = FakeRing()
        fake3.rangeEnd = 2
        fake3.collections[0] = byteArrayOf(1, 0, 0, 0)
        fake3.collections[1] = tap(3)
        val storage3 = MemIndex()
        val engine3 = engine(fake3, storage3, TestHacks(storage3))
        val events3 = collect(engine3)
        fake3.emit(count = 2)
        advanceUntilIdle()
        assertEquals(1, storage3.value)
        assertTrue(events3.snapshot().any { it is TransferStatus.IrrecoverableDataDetected })
        val gesture = events3.snapshot().filterIsInstance<TransferStatus.TransferTypeDetermined>().last()
        assertEquals(false, gesture.isAudio)
        assertEquals("short short short", gesture.buttonSequence)
        events3.cancel()
    }

    @Test
    fun missingDataSkipsTheIndex() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 2
        fake.failStatus[0] = 0x05
        fake.collections[1] = tap(1)
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val events = collect(engine)
        fake.emit(count = 2)
        advanceUntilIdle()
        assertEquals(1, storage.value)
        assertTrue(events.snapshot().any { it is TransferStatus.IrrecoverableDataDetected })
        events.cancel()
    }

    @Test
    fun tapsDoNotCompleteAndAudioDoes() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 2
        fake.collections[0] = tap(1)
        fake.collections[1] = collectionOf(clockRecords(50, 1), pcmRecord(1, 0))
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val events = collect(engine)
        fake.emit(count = 2)
        advanceUntilIdle()
        val types = events.snapshot().filterIsInstance<TransferStatus.TransferTypeDetermined>()
        assertEquals(false, types[0].isAudio)
        assertEquals(true, types[1].isAudio)
        val complete = events.snapshot().filterIsInstance<TransferStatus.TransferComplete>().single()
        assertEquals(1, complete.samples.size)
        assertEquals(52L, complete.buttonReleaseTimestamp?.epochSeconds)
        assertEquals(1, storage.value)
        events.cancel()
    }

    @Test
    fun crashBetweenCompleteAndPersistLeavesTheIndex() = runTest {
        val fake = FakeRing()
        fake.rangeEnd = 1
        fake.collections[0] = collectionOf(pcmRecord(1, 0))
        val storage = MemIndex()
        val engine = engine(fake, storage, TestHacks(storage))
        val job = launch {
            try {
                engine.startScanning().collect { status ->
                    val transfer = (status as? SatelliteStatus.Transferring)?.transferStatus
                    if (transfer is TransferStatus.TransferComplete) error("collector failed")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        advanceUntilIdle()
        fake.emit(count = 1)
        advanceUntilIdle()
        assertNull(storage.value)
        job.cancel()
    }

    @Test
    fun connectPolicyHonoursCountServicingRecordingAndIdentity() = runTest {
        val fake = FakeRing()
        val storage = MemIndex()
        val debug = TestDebug(readRssi = true)
        val engine = engine(fake, storage, TestHacks(storage), debug)
        val events = collect(engine)
        fake.emit(id = "OTHER", count = 1)
        advanceUntilIdle()
        assertEquals(0, fake.connectAttempts)

        fake.emit(count = 0)
        advanceUntilIdle()
        assertEquals(1, fake.connectAttempts)
        assertEquals("3.75.0", engine.satellite("RING")?.state?.value?.firmwareVersion)
        assertEquals(-40f, engine.satellite("RING")?.state?.value?.rxRSSI)
        assertEquals(1, fake.reads.count { it == gravel.ring.protocol.MemoryMap.VERSIONS })

        fake.emit(count = 0)
        advanceUntilIdle()
        assertEquals(1, fake.connectAttempts)

        fake.needsServicing = true
        fake.emit(count = 0, servicing = true)
        advanceUntilIdle()
        assertEquals(2, fake.connectAttempts)
        assertEquals(1, fake.clockWrites)
        assertTrue((fake.clockSeconds ?: 0) > 0)

        fake.recording = true
        fake.recordingReadsUntilClear = 2
        fake.emit(count = 0, recordingFlag = true)
        advanceUntilIdle()
        assertEquals(3, fake.connectAttempts)
        assertEquals(false, fake.recording)
        assertTrue(fake.politeDisconnects >= 3)
        assertEquals(1, fake.reads.count { it == gravel.ring.protocol.MemoryMap.VERSIONS })
        events.cancel()
    }

    @Test
    fun gatt137And133MapToBluetoothFailures() = runTest {
        val removed = FakeRing()
        removed.connectStatuses.add(137)
        val storage = MemIndex()
        val engine = engine(removed, storage, TestHacks(storage))
        val events = collect(engine)
        removed.emit(count = 1)
        advanceUntilIdle()
        assertEquals(1, removed.connectAttempts)
        val failure = events.snapshot().filterIsInstance<SatelliteStatus.BluetoothFailure>().single()
        assertEquals(BluetoothFailureReason.PeerRemovedPairingInformation, failure.reason)
        events.cancel()

        val flaky = FakeRing()
        repeat(6) { flaky.connectStatuses.add(133) }
        val engine2 = engine(flaky, MemIndex(), TestHacks(MemIndex()))
        val events2 = collect(engine2)
        flaky.emit(count = 1)
        advanceUntilIdle()
        assertEquals(6, flaky.connectAttempts)
        val again = events2.snapshot().filterIsInstance<SatelliteStatus.BluetoothFailure>().single()
        assertEquals(BluetoothFailureReason.ConnectFailed, again.reason)
        events2.cancel()

        val auth = FakeRing()
        auth.connectStatuses.add(15)
        val engine3 = engine(auth, MemIndex(), TestHacks(MemIndex()))
        val events3 = collect(engine3)
        auth.emit(count = 1)
        advanceUntilIdle()
        assertEquals(1, auth.connectAttempts)
        assertEquals(
            BluetoothFailureReason.AuthenticationFailed,
            events3.snapshot().filterIsInstance<SatelliteStatus.BluetoothFailure>().single().reason,
        )
        events3.cancel()
    }
}

private fun tap(count: Long) = collectionOf(tapRecord(0, count))

private fun engine(
    fake: FakeRing,
    storage: MemIndex,
    hacks: TestHacks,
    debug: TestDebug = TestDebug(),
) = liveEngine(
    transport = fake.transport(),
    storage = storage,
    hacks = hacks,
    debug = debug,
    pairedId = { "RING" },
    hwVersion = 11 to 0,
)

private class MemIndex(initial: Int? = null) : CollectionIndexStorage {
    private val flow = MutableStateFlow(initial)
    val value: Int? get() = flow.value
    override val lastSuccessfulCollectionIndex: StateFlow<Int?> = flow
    override fun setLastSuccessfulCollectionIndex(index: Int?) {
        flow.value = index
    }
}

private class TestHacks(
    private val storage: MemIndex,
    var wipe: Boolean = false,
    var nullsIndex: Boolean = false,
) : KMPHaversineHacksDelegate {
    var wiped: Int = 0
    override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = wipe
    override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) {
        wiped++
        if (nullsIndex) storage.setLastSuccessfulCollectionIndex(null)
    }
}

private class TestDebug(var readRssi: Boolean = false) : KMPHaversineDebugDelegate {
    override fun handleHaversineDebugInfo(info: coredevices.haversine.KMPHaversineDebugInfo) = Unit
    override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite) = readRssi
    override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) = Unit
}

private class EventLog {
    private val items = mutableListOf<TransferOrFailure>()
    fun add(status: SatelliteStatus) {
        when (status) {
            is SatelliteStatus.Transferring -> items += status.transferStatus
            is SatelliteStatus.BluetoothFailure -> items += status
            else -> Unit
        }
    }

    fun snapshot(): List<TransferOrFailure> = items.toList()
    fun none(predicate: (TransferOrFailure) -> Boolean) = items.none(predicate)
    fun any(predicate: (TransferOrFailure) -> Boolean) = items.any(predicate)
    lateinit var cancel: () -> Unit
}

private typealias TransferOrFailure = Any

private fun kotlinx.coroutines.CoroutineScope.collect(engine: RingEngine): EventLog {
    val log = EventLog()
    val job = launch {
        engine.startScanning().collect { log.add(it) }
    }
    log.cancel = { job.cancel() }
    return log
}
