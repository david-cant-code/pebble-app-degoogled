package coredevices.haversine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class KMPHaversineSatellite internal constructor(
    val id: String,
    name: String?,
    private val ops: SatelliteOps,
) {
    var name: String? = name
        internal set

    private val _state = MutableStateFlow<KMPHaversineSatelliteState?>(null)
    val state: StateFlow<KMPHaversineSatelliteState?> = _state

    var lastAdvertisement: KMPHaversineAdvertisement? = null
        internal set

    suspend fun eraseCollections() = ops.eraseCollections(this)

    suspend fun forceFailsafe(): Unit = throw HaversineFeatureUnavailable("forceFailsafe")

    internal fun replaceState(next: KMPHaversineSatelliteState?) {
        _state.value = next
    }
}

internal interface SatelliteOps {
    suspend fun eraseCollections(satellite: KMPHaversineSatellite)
}

class KMPHaversineSatelliteState(
    val firmwareVersion: String,
    val serialNumber: String,
    val programmedSerialNumber: String?,
    val rxRSSI: Float?,
    val isInFailsafeMode: Boolean,
    val batteryMillivolts: Int?,
    /**
     * ASSUMPTION: this is the advertisement's recording-in-progress flag.
     * `RingSync.lastRingSummary` prints it; the spec does not name the field.
     */
    val isInCollectionState: Boolean,
    /**
     * ASSUMPTION: true once an advertisement has been seen for this id.
     */
    val isNearby: Boolean,
    /**
     * ASSUMPTION: advertisement byte 4, the low 8 bits of the collection counter.
     */
    val truncatedCollectionCount: Int?,
)

internal fun KMPHaversineSatelliteState?.merged(
    firmwareVersion: String? = null,
    serialNumber: String? = null,
    programmedSerialNumber: String? = null,
    programmedSerialPresent: Boolean = false,
    rxRSSI: Float? = null,
    rxRSSIPresent: Boolean = false,
    isInFailsafeMode: Boolean? = null,
    batteryMillivolts: Int? = null,
    batteryPresent: Boolean = false,
    isInCollectionState: Boolean? = null,
    isNearby: Boolean? = null,
    truncatedCollectionCount: Int? = null,
    countPresent: Boolean = false,
): KMPHaversineSatelliteState {
    val prev = this
    return KMPHaversineSatelliteState(
        firmwareVersion = firmwareVersion ?: prev?.firmwareVersion ?: "",
        serialNumber = serialNumber ?: prev?.serialNumber ?: "",
        programmedSerialNumber = if (programmedSerialPresent) programmedSerialNumber else prev?.programmedSerialNumber,
        rxRSSI = if (rxRSSIPresent) rxRSSI else prev?.rxRSSI,
        isInFailsafeMode = isInFailsafeMode ?: prev?.isInFailsafeMode ?: false,
        batteryMillivolts = if (batteryPresent) batteryMillivolts else prev?.batteryMillivolts,
        isInCollectionState = isInCollectionState ?: prev?.isInCollectionState ?: false,
        isNearby = isNearby ?: prev?.isNearby ?: false,
        truncatedCollectionCount = if (countPresent) truncatedCollectionCount else prev?.truncatedCollectionCount,
    )
}
