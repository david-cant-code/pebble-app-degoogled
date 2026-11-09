package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import com.anopticlabs.gravel.ui.RingSyncStatus
import coredevices.libindex.device.DiscoveredIndexDevice
import coredevices.libindex.device.IndexImage
import coredevices.libindex.device.IndexPairingResult
import coredevices.libindex.device.IndexPairingState
import coredevices.libindex.device.InterviewedIndexDevice
import coredevices.libindex.device.KnownIndexDevice
import coredevices.libindex.device.PairingRequestResult
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ADDRESS = "A1:B2:C3:D4:E5:F6"
private const val OTHER = "A1:B2:C3:D4:E5:F7"

/** Android's bonds as a map; a missing address has no bond, a null entry cannot be read. */
private class FakeBonds : RingBonds {
    val bonds = mutableMapOf<String, RingBond?>()
    val created = mutableListOf<String>()
    var creates = true
    var bondOnCreate: RingBond? = RingBond.BONDED
    override val changes = MutableSharedFlow<RingBondChange>(extraBufferCapacity = 16)
    override val bluetoothOn = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    override fun bond(address: String): RingBond? = if (address in bonds) bonds[address] else RingBond.NONE

    override fun create(address: String): Boolean {
        created += address
        if (creates) bonds[address] = bondOnCreate
        return creates
    }
}

private fun advertisement(fingerprint: Long = 0x1234_FFFF) = RingAdvertisement(fingerprint, 0, needsServicing = false, recording = false)

private fun manufacturerData(fingerprint: Long = 0x1234_FFFF) =
    byteArrayOf(fingerprint.toByte(), (fingerprint shr 8).toByte(), (fingerprint shr 16).toByte(), (fingerprint shr 24).toByte(), 3, 0)

class HeardRingTest {
    @Test
    fun aRingsAdvertisementIsReadWithItsNameAndSignal() {
        val ring = heardRing(ADDRESS, "Pebble Index 4F6", -60, manufacturerData())

        assertEquals(HeardRing(ADDRESS, "Pebble Index 4F6", -60, RingAdvertisement(0x1234_FFFF, 3, false, false)), ring)
    }

    @Test
    fun anythingButARingsAdvertisementAtAnAndroidAddressIsNotARing() {
        assertNull(heardRing(ADDRESS, "x", 0, null))
        assertNull(heardRing(ADDRESS, "x", 0, byteArrayOf(1, 2, 3)))
        assertNull(heardRing("a1:b2:c3:d4:e5:f6", "x", 0, manufacturerData()))
        assertNull(heardRing("A1B2C3D4E5F6", "x", 0, manufacturerData()))
    }

    @Test
    fun aMissingNameReadsAsTheRingsAndALongOneIsCutOnWholeCharacters() {
        assertEquals("Index 01", shownRingName(null))
        assertEquals("Index 01", shownRingName(" "))
        assertEquals("x".repeat(32), shownRingName("x".repeat(40)))
        val emoji = String(Character.toChars(0x1F48D))
        assertEquals(emoji.repeat(32), shownRingName(emoji.repeat(40)))
    }

    @Test
    fun controlAndFormatCharactersAreLeftOut() {
        val rightToLeftOverride = String(Character.toChars(0x202E))
        val zeroWidthJoiner = String(Character.toChars(0x200D))
        val bell = String(Character.toChars(7))
        assertEquals("Pebble Index", shownRingName("Pebble$rightToLeftOverride Index$zeroWidthJoiner$bell"))
        assertEquals("Index 01", shownRingName(rightToLeftOverride + bell))
        val lineSeparator = String(Character.toChars(0x2028))
        val paragraphSeparator = String(Character.toChars(0x2029))
        assertEquals("Pebble Index", shownRingName("Pebble$lineSeparator Index$paragraphSeparator"))
    }
}

class RingClientLibIndexTest {
    private val dir: File = createTempDirectory("ring").toFile()
    private val file = PairedRingFile(dir)
    private val bonds = FakeBonds()
    private val nearby = Channel<HeardRing>(Channel.UNLIMITED)
    private val granted = MutableStateFlow(true)
    private var readable = true
    private var startOverFails = false

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun TestScope.runner() = RingSyncRunner(
        paired = file.address,
        hear = { emptyFlow() },
        sync = { _, _ -> RingSessionEnd.SYNCED },
        lastCount = { null },
        positionReadable = { readable },
        waitReason = { null },
        lastWait = { null },
        imported = { 0 },
        startOver = { if (startOverFails) error("not cleared") },
        clock = { testScheduler.currentTime },
        marks = testScheduler.timeSource,
    )

    private fun TestScope.libIndex(runner: RingSyncRunner = runner()): RingClientLibIndex =
        RingClientLibIndex(
            pairedRing = file,
            bonds = bonds,
            runner = runner,
            hearNearby = { nearby.receiveAsFlow() },
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
        ).also {
            it.init(granted)
            runCurrent()
        }

    private fun TestScope.hear(address: String, fingerprint: Long = 0x1234_FFFF) {
        nearby.trySend(HeardRing(address, "Pebble Index", -50, advertisement(fingerprint)))
        runCurrent()
    }

    private fun discovered(index: RingClientLibIndex, address: String = ADDRESS): DiscoveredIndexDevice =
        index.rings.value.filterIsInstance<DiscoveredIndexDevice>().single { it.identifier.asPlatformAddress == address }

    @Test
    fun aSearchListsTheRingsItHearsUntilItIsStopped() = runTest {
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        hear(OTHER)

        assertTrue(index.isScanning.value)
        assertEquals(listOf(ADDRESS, OTHER), index.rings.value.map { it.identifier.asPlatformAddress })
        assertEquals(IndexImage.Primary, discovered(index).currentImage)
        index.stopScan()
        runCurrent()
        assertFalse(index.isScanning.value)
        assertTrue(index.rings.value.isEmpty())
    }

    @Test
    fun aSearchEndsAfterThirtySecondsAndKeepsWhatItHeardUntilTheNextOne() = runTest {
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        advanceTimeBy(30.seconds)
        runCurrent()

        assertFalse(index.isScanning.value)
        assertEquals(1, index.rings.value.size)
        index.startScan()
        runCurrent()
        assertTrue(index.rings.value.isEmpty())
    }

    @Test
    fun aSearchListsAtMostSixteenRings() = runTest {
        val index = libIndex()
        index.startScan()
        repeat(20) { hear("A1:B2:C3:D4:E5:%02X".format(it)) }

        assertEquals(16, index.rings.value.size)
    }

    @Test
    fun aRingInItsFailsafeOrFactoryTestImageIsListedAsSuchAndNotPaired() = runTest {
        val index = libIndex()
        index.startScan()
        hear(ADDRESS, 0xDEADDEAD)
        hear(OTHER, 0xDEADBEEF)

        assertEquals(IndexImage.Failsafe, discovered(index).currentImage)
        assertEquals(IndexImage.ProductionTest, discovered(index, OTHER).currentImage)
        assertIs<IndexPairingResult.PairingFailure>(discovered(index).pair())
        assertTrue(bonds.created.isEmpty())
    }

    @Test
    fun pairingBondsAndKeepsTheRingAsThePairedOne() = runTest {
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        hear(OTHER)

        assertEquals(IndexPairingResult.Success, discovered(index).pair())
        runCurrent()
        assertEquals(listOf(ADDRESS), bonds.created)
        assertEquals(ADDRESS, PairedRingFile(dir).apply { load() }.address.value)
        val ring = index.rings.value.single()
        assertIs<KnownIndexDevice>(ring)
        assertIs<RingSyncStatus>(ring)
        assertFalse(ring is InterviewedIndexDevice)
        assertFalse(index.isScanning.value)
    }

    @Test
    fun theBondingReportDoesNotEndPairingAndTheBondDoes() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        val pairing = backgroundScope.async { discovered(index).pair() }
        runCurrent()
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDING))
        runCurrent()
        assertFalse(pairing.isCompleted)
        assertEquals(IndexPairingState.Pairing, discovered(index).pairingState)
        bonds.bonds[ADDRESS] = RingBond.BONDED
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDED))
        runCurrent()
        assertEquals(IndexPairingResult.Success, pairing.await())
    }

    @Test
    fun aBondRefusedForAuthenticationReadsAsARingPairedElsewhere() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        val pairing = backgroundScope.async { discovered(index).pair() }
        runCurrent()
        bonds.bonds[ADDRESS] = RingBond.NONE
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.NONE, LEConstants.UNBOND_REASON_AUTH_FAILED))
        runCurrent()
        val result = assertIs<IndexPairingResult.PairingFailure>(pairing.await())
        assertEquals(PairingRequestResult.RingAlreadyPaired, result.cause)
        assertIs<IndexPairingState.Error>(discovered(index).pairingState)
        assertNull(file.address.value)
    }

    @Test
    fun leavingTheScreenDoesNotStopAPairing() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        val screen = launch { discovered(index).pair() }
        runCurrent()
        screen.cancel()
        bonds.bonds[ADDRESS] = RingBond.BONDED
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDED))
        runCurrent()
        assertEquals(ADDRESS, file.address.value)
    }

    @Test
    fun aSecondRingIsNotPairedWhileTheFirstIsPairing() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        hear(OTHER)

        backgroundScope.launch { discovered(index).pair() }
        runCurrent()
        assertIs<IndexPairingResult.PairingFailure>(discovered(index, OTHER).pair())
        assertIs<IndexPairingResult.PairingFailure>(discovered(index).pair())
        runCurrent()
        assertEquals(listOf(ADDRESS), bonds.created)
        assertEquals(IndexPairingState.Pairing, discovered(index).pairingState, "a second tap leaves the running pairing alone")
    }

    @Test
    fun aRingAndroidHasABondWithAlreadyPairsWithoutANewBond() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        assertEquals(IndexPairingResult.Success, discovered(index).pair())
        assertTrue(bonds.created.isEmpty())
    }

    @Test
    fun aFailedBondLeavesTheRingListedWithItsError() = runTest {
        bonds.creates = false
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        val result = assertIs<IndexPairingResult.PairingFailure>(discovered(index).pair())
        runCurrent()
        assertEquals(PairingRequestResult.CreateBondFailed, result.cause)
        assertIs<IndexPairingState.Error>(discovered(index).pairingState)
        assertNull(file.address.value)
    }

    @Test
    fun aPairedRingThatCannotBeSavedIsAFailure() = runTest {
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        dir.deleteRecursively()
        dir.writeText("not a directory")

        assertIs<IndexPairingResult.PairingFailure>(discovered(index).pair())
        assertNull(file.address.value)
    }

    @Test
    fun aSecondRingIsNotPairedWhileOneIs() = runTest {
        bonds.bonds[OTHER] = RingBond.BONDED
        file.save(OTHER)
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)

        assertIs<IndexPairingResult.PairingFailure>(discovered(index).pair())
        assertTrue(bonds.created.isEmpty())
    }

    @Test
    fun thePairedRingIsListedOnceEvenWhenASearchHearsIt() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        file.save(ADDRESS)
        val index = libIndex()
        index.startScan()
        hear(ADDRESS)
        hear(OTHER)

        assertEquals(listOf(ADDRESS, OTHER), index.rings.value.map { it.identifier.asPlatformAddress })
        assertIs<KnownIndexDevice>(index.rings.value.first())
    }

    @Test
    fun onlyAnAddressInTheSavedFileIsTheSavedRing() {
        for (bad in listOf("a1:b2:c3:d4:e5:f6", "A1B2C3D4E5F6", "A1:B2:C3:D4:E5:F6:00", "junk")) {
            File(dir, "ring_paired").writeText(bad)
            assertNull(PairedRingFile(dir).apply { load() }.address.value, bad)
        }
        File(dir, "ring_paired").writeText(ADDRESS)
        assertEquals(ADDRESS, PairedRingFile(dir).apply { load() }.address.value)
    }

    @Test
    fun whenABondReadFindsThePairedRingGoneTheRingIsForgotten() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        file.save(ADDRESS)
        val index = libIndex()
        bonds.changes.emit(RingBondChange(OTHER, RingBond.NONE))
        runCurrent()
        assertEquals(ADDRESS, file.address.value, "another device's bond")

        bonds.bonds[ADDRESS] = RingBond.BONDING
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDING))
        runCurrent()
        assertEquals(ADDRESS, file.address.value, "bonding again")

        bonds.bonds[ADDRESS] = null
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.NONE))
        runCurrent()
        assertEquals(ADDRESS, file.address.value, "a bond that cannot be read is kept")

        bonds.bonds[ADDRESS] = RingBond.NONE
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDED))
        runCurrent()
        assertNull(file.address.value, "the read decides, not the report")
        assertTrue(index.rings.value.isEmpty())
    }

    @Test
    fun aRingBondedOutsideGravelIsNotTakenAsThePairedRing() = runTest {
        val index = libIndex()
        bonds.bonds[ADDRESS] = RingBond.BONDED
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDED))
        runCurrent()

        assertNull(file.address.value)
        assertTrue(index.rings.value.isEmpty())
    }

    @Test
    fun aPairedRingWithoutABondIsForgottenAtStartOnlyWhenTheBondCanBeRead() = runTest {
        file.save(ADDRESS)
        bonds.bonds[ADDRESS] = null
        granted.value = false
        libIndex()
        assertEquals(ADDRESS, file.address.value)

        bonds.bonds[ADDRESS] = RingBond.NONE
        granted.value = true
        runCurrent()
        assertNull(file.address.value)
    }

    @Test
    fun aBondRemovedWhileBluetoothWasOffIsFoundWhenItTurnsOn() = runTest {
        file.save(ADDRESS)
        bonds.bonds[ADDRESS] = null
        libIndex()
        assertEquals(ADDRESS, file.address.value)

        bonds.bonds[ADDRESS] = RingBond.NONE
        bonds.bluetoothOn.emit(Unit)
        runCurrent()
        assertNull(file.address.value)
    }

    @Test
    fun removingThePairedRingForgetsIt() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        file.save(ADDRESS)
        val index = libIndex()

        (index.rings.value.single() as KnownIndexDevice).remove()
        runCurrent()
        assertNull(file.address.value)
        assertTrue(index.rings.value.isEmpty())
    }

    @Test
    fun thePairedRingOffersImportAgainWhileItsPositionIsLostAndRunsIt() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        file.save(ADDRESS)
        readable = false
        val runner = runner()
        val index = libIndex(runner)
        runner.start(backgroundScope)
        runCurrent()
        val ring = index.rings.value.single() as RingSyncStatus

        assertTrue(ring.canImportAgain.value)
        startOverFails = true
        ring.importAgain()
        assertTrue(ring.canImportAgain.value, "a position that was not cleared stays lost")
        startOverFails = false
        ring.importAgain()
        assertFalse(ring.canImportAgain.value)
    }

    @Test
    fun thePairedRingsLineFollowsTheRunner() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        file.save(ADDRESS)
        readable = false
        val runner = runner()
        val index = libIndex(runner)
        val ring = index.rings.value.single() as RingSyncStatus
        backgroundScope.launch { ring.statusLine.collect {} }
        runCurrent()
        assertEquals("Not syncing: Gravel's background service isn't running", ring.statusLine.value)

        runner.start(backgroundScope)
        runCurrent()
        assertEquals("Syncing stopped: Gravel lost track of which recordings it imported", ring.statusLine.value)
    }
}

class BondRingTest {
    private val bonds = FakeBonds()

    @Test
    fun aBondedRingNeedsNoNewBond() = runTest {
        bonds.bonds[ADDRESS] = RingBond.BONDED
        assertEquals(BondOutcome.Bonded, bondRing(bonds, ADDRESS))
        assertTrue(bonds.created.isEmpty())
    }

    @Test
    fun aBondAndroidRefusesToStartFails() = runTest {
        bonds.creates = false
        assertEquals(BondOutcome.NotStarted, bondRing(bonds, ADDRESS))
    }

    @Test
    fun aReportOfNoBondThatAReadConfirmsEndsTheWaitWithItsReason() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val bonding = backgroundScope.async { bondRing(bonds, ADDRESS) }
        runCurrent()
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.BONDING))
        runCurrent()
        assertFalse(bonding.isCompleted, "bonding started")
        bonds.changes.emit(RingBondChange(ADDRESS, RingBond.NONE, 3))
        runCurrent()
        assertFalse(bonding.isCompleted, "the read still says bonding")
        bonds.bonds[ADDRESS] = RingBond.NONE
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(BondOutcome.Ended(3), bonding.await(), "the one report Android sends, confirmed by a later read")
    }

    @Test
    fun aBondIsFoundByReadingEvenWithoutAReportedChange() = runTest {
        bonds.bondOnCreate = RingBond.BONDING
        val bonding = backgroundScope.async { bondRing(bonds, ADDRESS) }
        advanceTimeBy(5.seconds)
        bonds.bonds[ADDRESS] = RingBond.BONDED
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(BondOutcome.Bonded, bonding.await())
    }

    @Test
    fun noBondWithinTheTimeoutFails() = runTest {
        bonds.bondOnCreate = null
        assertEquals(BondOutcome.TimedOut, bondRing(bonds, ADDRESS))
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun theUnbondReasonsReadAsUpstreamReadsThem() {
        assertEquals(PairingRequestResult.RingAlreadyPaired, bondFailure(LEConstants.UNBOND_REASON_AUTH_FAILED))
        assertEquals(PairingRequestResult.RingAlreadyPaired, bondFailure(LEConstants.UNBOND_REASON_AUTH_REJECTED))
        assertEquals(PairingRequestResult.UserRejected, bondFailure(LEConstants.UNBOND_REASON_AUTH_CANCELLED))
        assertIs<PairingRequestResult.Error>(bondFailure(9))
        assertIs<PairingRequestResult.Error>(bondFailure(null))
    }
}

class RingStatusLineTest {
    private val time = { millis: Long -> "t$millis" }
    private val now = 10_000_000L

    private fun line(state: RingSyncState, heard: Long? = now, searchFailed: Boolean = false) =
        ringStatusLine(state, searchFailed, heard, now, time)

    @Test
    fun eachStateHasItsLine() {
        assertEquals("Waiting to hear from the ring", line(RingSyncState.Listening, null))
        assertEquals("Last seen at t$now", line(RingSyncState.Listening))
        assertEquals("Syncing", line(RingSyncState.Syncing))
        assertEquals("Synced at t5, nothing new", line(RingSyncState.Synced(5, 0)))
        assertEquals("Synced at t5, 1 recording added", line(RingSyncState.Synced(5, 1)))
        assertEquals("Synced at t5, 3 recordings added", line(RingSyncState.Synced(5, 3)))
        assertEquals("Sync failed at t5, trying again", line(RingSyncState.Failed(5)))
        assertEquals("Recordings stay on the ring while the Notebook is off", line(RingSyncState.Waiting(RingWait.NOTEBOOK_OFF)))
        assertEquals(
            "Recordings stay on the ring while 50 ring recordings wait in the Notebook, flagged ones included",
            line(RingSyncState.Waiting(RingWait.TOO_MANY_WAITING)),
        )
        assertEquals("Recordings stay on the ring for now: 60 came in the past hour", line(RingSyncState.Waiting(RingWait.HOURLY_LIMIT)))
        assertEquals("Recordings stay on the ring: the Notebook could not keep the last one", line(RingSyncState.Waiting(RingWait.NOT_KEPT)))
        assertEquals("Forget this ring in Android's Bluetooth settings, then pair it again", line(RingSyncState.PairAgain))
        assertEquals("Syncing stopped: Gravel lost track of which recordings it imported", line(RingSyncState.PositionLost))
        assertEquals("Not syncing: Gravel's background service isn't running", line(RingSyncState.Stopped))
    }

    @Test
    fun aFailedSearchIsSaidExceptWhileSyncingStoppedOrStuck() {
        assertEquals("Can't search for the ring right now", line(RingSyncState.Synced(5, 0), searchFailed = true))
        assertEquals("Can't search for the ring right now", line(RingSyncState.Listening, null, searchFailed = true))
        assertEquals("Syncing", line(RingSyncState.Syncing, searchFailed = true))
        assertEquals("Syncing stopped: Gravel lost track of which recordings it imported", line(RingSyncState.PositionLost, searchFailed = true))
        assertEquals("Not syncing: Gravel's background service isn't running", line(RingSyncState.Stopped, searchFailed = true))
    }

    @Test
    fun aRingNotHeardForHalfAnHourAfterASyncSaysWhenItWasLastSeen() {
        val heard = now - 30 * 60 * 1000L
        assertEquals("Synced at t5, nothing new", line(RingSyncState.Synced(5, 0), heard))
        assertEquals("Not seen since t${heard - 1}", line(RingSyncState.Synced(5, 0), heard - 1))
        assertEquals("Not seen since t${heard - 1}", line(RingSyncState.Failed(5), heard - 1))
        assertEquals("Syncing stopped: Gravel lost track of which recordings it imported", line(RingSyncState.PositionLost, 0))
    }
}
