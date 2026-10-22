package coredevices.haversine

import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

interface CollectionIndexStorage {
    val lastSuccessfulCollectionIndex: StateFlow<Int?>
    fun setLastSuccessfulCollectionIndex(index: Int?)
}

interface KMPHaversineHacksDelegate {
    fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite): Boolean
    fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite)
}

interface KMPHaversineDebugDelegate {
    fun handleHaversineDebugInfo(info: KMPHaversineDebugInfo)
    fun shouldReadRxRSSI(satellite: KMPHaversineSatellite): Boolean
    fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite)
}

/**
 * Debug dumps are never produced in v1. The shape matches the fields
 * `FirestoreRingDebugDelegate` reads.
 */
class KMPHaversineDebugInfo(
    val timestamp: Instant,
    val satelliteId: String,
    val satelliteName: String,
    val satelliteVersion: String,
    val satelliteSerial: String,
    val satelliteProgrammedSerial: String?,
    val dump: KMPHaversineDebugDump,
)

class KMPHaversineDebugDump(
    val coreDump: ByteArray?,
    val rebootReasons: List<KMPHaversineDebugRebootReason>,
)

class KMPHaversineDebugRebootReason(
    val code: UInt,
    val context: UInt,
    val description: String?,
)
