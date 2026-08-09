package coredevices.libindex.device

import co.touchlab.kermit.Logger
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.libindex.IndexDevices
import coredevices.libindex.Rings
import coredevices.libindex.database.BasePreferences
import coredevices.libindex.database.PrefsCollectionIndexStorage
import coredevices.libindex.database.repository.RingTransferRepository
import coredevices.libindex.di.LibIndexCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class IndexDeviceManager(
    private val satelliteManager: KMPHaversineSatelliteManager,
    private val scope: LibIndexCoroutineScope,
    private val deviceFactory: IndexDeviceFactory,
    private val prefs: BasePreferences,
    // Not present on some platforms (iOS)
    private val associations: IndexPlatformBluetoothAssociations?,
    private val indexStorage: PrefsCollectionIndexStorage,
    private val transferRepo: RingTransferRepository,
): Rings {
    private val _rings = MutableStateFlow(emptyList<IndexDevice>())
    override val rings: IndexDevices = _rings

    override fun warnIfNoCompanionAssociations() {
        associations?.warnIfNoCompanionAssociations()
    }

    companion object {
        private val logger = Logger.withTag("IndexDeviceRepository")
    }

    fun update(indexDevice: IndexDevice) {
        _rings.update { prev ->
            val existingIdx = prev.indexOfFirst { indexDevice.identifier.asString.equals(it.identifier.asString, ignoreCase = true) }
            if (existingIdx != -1) {
                prev
                    .toMutableList()
                    .apply { set(existingIdx, indexDevice) }
            } else {
                prev + indexDevice
            }
        }
    }

    private fun updateRing(satellite: KMPHaversineSatellite, isUpdating: Boolean? = null) {
        _rings.update { prev ->
            val existingIdx = prev.indexOfFirst { satellite.id.equals(it.identifier.asString, ignoreCase = true) }
            val existing = if (existingIdx != -1) prev[existingIdx] as? KnownIndexDevice else null
            if (existingIdx != -1 && existing != null) {
                if (prefs.ringPairedName.value != satellite.name) {
                    prefs.setRingPairedName(satellite.name)
                }
                prev
                    .toMutableList()
                    .apply {
                        set(
                            existingIdx,
                            deviceFactory.create(
                                identifier = existing.identifier,
                                name = existing.name,
                                isPaired = true,
                                satellite = satellite,
                                satelliteState = satellite.state.value!!,
                                isUpdating = isUpdating
                                    ?: (existing is InterviewedIndexDevice && (existing as InterviewedIndexDevice).updating)
                            )
                        )
                    }
            } else {
                prev
            }
        }
    }

    fun init() {
        scope.launch {
            // Re-reconcile on every change so the stored ring can't get stuck out of sync
            // with the platform bond list (e.g. unbonded while BLUETOOTH_CONNECT was denied).
            var warned = false
            associations?.associations?.collect { loaded ->
                val current = StoredPairing(prefs.ringPaired.value, prefs.ringPairedName.value)
                val decision = resolvePairedRing(
                    storedPairedId = current.id,
                    storedPairedName = current.name,
                    associations = loaded,
                )
                when (decision) {
                    PairedRingDecision.Clear ->
                        logger.d { "Paired ring ${current.id} not found in loaded bt associations, clearing paired state" }
                    is PairedRingDecision.Adopt ->
                        logger.d { "Found candidate ${decision.name} (${decision.identifier}) in bt associations, setting as paired ring" }
                    else -> {}
                }
                val next = current.applyDecision(decision)
                if (next != current) {
                    prefs.setRingPaired(next.id)
                    prefs.setRingPairedName(next.name)
                }
                if (decision is PairedRingDecision.Adopt) {
                    // Runs after the prefs write above, so a later emission resolves to Keep
                    // instead of wiping again.
                    scope.launch(Dispatchers.IO) {
                        indexStorage.setLastSuccessfulCollectionIndex(null)
                        transferRepo.markTransfersAsPreviousIndexIteration()
                    }
                }
                if (loaded != null && !warned) {
                    warned = true
                    warnIfNotCompanionAssociated()
                }
            }
        }
        associations?.bondStateChanges?.onEach { evt ->
            if (evt.state == IndexBondState.NotBonded && evt.identifier.asString == prefs.ringPaired.value) {
                logger.d { "Received bond state change for paired ring ${evt.identifier.asString}, state=${evt.state}, removing paired state" }
                prefs.setRingPaired(null)
                prefs.setRingPairedName(null)
            } else if (evt.state == IndexBondState.Bonded && evt.identifier.asString == prefs.ringPaired.value) {
                logger.d { "Received bond state change for paired ring ${evt.identifier.asString}, state=${evt.state}, ring likely SOS'd, considering it a new iteration" }
                indexStorage.setLastSuccessfulCollectionIndex(null)
                transferRepo.markTransfersAsPreviousIndexIteration()
                evt.name?.let { prefs.setRingPairedName(it) }
            } else if (evt.state == IndexBondState.Bonded && prefs.ringPaired.value == null && evt.name?.contains("Pebble Index", ignoreCase = true) == true) {
                logger.d { "Received bond state change for unpaired ring ${evt.identifier.asString}, state=${evt.state}, setting as paired ring" }
                indexStorage.setLastSuccessfulCollectionIndex(null)
                transferRepo.markTransfersAsPreviousIndexIteration()
                prefs.setRingPaired(evt.identifier.asString)
                prefs.setRingPairedName(evt.name)
            }
        }?.flowOn(Dispatchers.IO)?.launchIn(scope)
        prefs.ringPaired
            .runningFold<String?, Pair<String?, String?>>(null to null) { (_, prev), new -> prev to new }
            .drop(1)
            .onEach { (old, new) ->
                logger.d { "Paired ring changed from $old to $new" }
                // remove old if it isnt the same as new, otherwise keep it to avoid UI flickering
                if (old != null && old != new) {
                    logger.d { "Removing old from list" }
                    _rings.update { prev ->
                        prev.filterNot { it.identifier.asString.equals(old, ignoreCase = true) }
                    }
                }
                if (new != null) {
                    logger.d { "Adding new to list" }
                    _rings.update { prev ->
                        // replace if exists and is a DiscoveredIndexDevice, otherwise add
                        val existing = prev.indexOfFirst { it.identifier.asString.equals(new, ignoreCase = true) }
                        val known = deviceFactory.create(
                            identifier = IndexIdentifier(new),
                            name = prefs.ringPairedName.value ?: "Index 01",
                            isPaired = true,
                        )
                        if (existing != -1 && prev[existing] is DiscoveredIndexDevice) {
                            prev
                                .toMutableList()
                                .apply { set(existing, known) }
                        } else {
                            prev + known
                        }
                    }
                }
            }.launchIn(scope)
        satelliteManager.lastRing
            .onEach {
                if (it != null) {
                    // Wait for state to be non-null, just in case
                    withTimeoutOrNull(1.seconds) {
                        it.state.filterNotNull().first()
                    } ?: return@onEach

                    updateRing(it, isUpdating = null)
                }
            }.launchIn(scope)
    }

    // Without any CompanionDeviceManager association the app loses companion background privileges
    // (e.g. launch activities) so warn the user to re-pair. Associations for other
    // devices (e.g. a watch) are fine and still grant the privileges.
    private fun warnIfNotCompanionAssociated() {
        val associations = associations ?: return
        val pairedId = prefs.ringPaired.value ?: return
        associations.warnIfNoCompanionAssociations()
    }

    fun markFirmwareUpdatingState(identifier: KMPHaversineSatellite, isUpdating: Boolean) {
        updateRing(identifier, isUpdating)
    }

    fun addScanResult(result: IndexScanResult) {
        _rings.update { prev ->
            // Match by identifier, or by name: a ring entering failsafe advertises a
            // slightly different address/id but the same name, so overwrite the existing
            // discovered entry rather than showing a duplicate.
            val existingIdx = prev.indexOfFirst {
                it.identifier.asString.equals(result.identifier.asString, ignoreCase = true) ||
                    (it is DiscoveredIndexDevice && it.name == result.name)
            }
            val existing = existingIdx.takeIf { it != -1 }?.let { prev[it] }
            if (existing is DiscoveredIndexDevice) {
                prev
                    .toMutableList()
                    .apply {
                        set(
                            existingIdx,
                            deviceFactory.create(
                                identifier = result.identifier,
                                name = result.name,
                                scanResult = result,
                                pairingState = existing.pairingState,
                            )
                        )
                    }
            } else if (existing == null) {
                prev + deviceFactory.create(
                    identifier = result.identifier,
                    name = result.name,
                    scanResult = result,
                )
            } else { // existing is a KnownIndexDevice, ignore scan result
                prev
            }
        }
    }

    fun clearScanResults() {
        _rings.update { prev ->
            prev.filterNot { it is DiscoveredIndexDevice }
        }
    }
}

data class IndexScanResult(
    val identifier: IndexIdentifier,
    val name: String,
    val rssi: Int,
    val isFailsafe: Boolean
)

internal data class StoredPairing(val id: String?, val name: String?)

/** Pure state transition; the caller keeps the side effects (collection wipe, logging). */
internal fun StoredPairing.applyDecision(decision: PairedRingDecision): StoredPairing =
    when (decision) {
        PairedRingDecision.Keep -> this
        PairedRingDecision.Clear -> StoredPairing(null, null)
        is PairedRingDecision.UpdateName -> copy(name = decision.name)
        is PairedRingDecision.Adopt -> StoredPairing(decision.identifier, decision.name)
    }

internal sealed interface PairedRingDecision {
    data object Keep : PairedRingDecision
    data object Clear : PairedRingDecision
    data class UpdateName(val name: String) : PairedRingDecision
    data class Adopt(val identifier: String, val name: String) : PairedRingDecision
}

/**
 * Pure decision for what to do with the stored paired ring, re-evaluated whenever the
 * association list changes.
 *
 * [associations] is null when the platform has no bt association support (iOS) or the
 * list has not been loaded yet (BLUETOOTH_CONNECT not granted / BT off). Must never
 * resolve to [PairedRingDecision.Clear] in that case.
 */
internal fun resolvePairedRing(
    storedPairedId: String?,
    storedPairedName: String?,
    associations: List<IndexAssociation>?,
): PairedRingDecision {
    if (associations == null) return PairedRingDecision.Keep
    if (storedPairedId != null) {
        val match = associations.firstOrNull { it.identifier == IndexIdentifier(storedPairedId) }
        return when {
            match == null -> PairedRingDecision.Clear
            match.deviceName != storedPairedName -> PairedRingDecision.UpdateName(match.deviceName)
            else -> PairedRingDecision.Keep
        }
    }
    val candidate = associations.firstOrNull { it.deviceName.contains("Pebble Index", ignoreCase = true) }
    return candidate?.let { PairedRingDecision.Adopt(it.identifier.asString, it.deviceName) }
        ?: PairedRingDecision.Keep
}