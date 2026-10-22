package coredevices.haversine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The zero-argument constructor is inert. Android and iOS actuals add the
 * constructors the experimental ring module already calls; those build a live engine.
 * A handle from the inert constructor never scans and never connects.
 */
expect class KMPHaversineSatelliteManager() {
    val lastRing: StateFlow<KMPHaversineSatellite?>

    suspend fun getSatelliteById(id: String): KMPHaversineSatellite?

    fun startScanning(): Flow<SatelliteStatus>

    suspend fun awaitBluetoothReady()

    fun restartPreemptiveTransfer()

    suspend fun programSatelliteWithUserID(satelliteId: String, uid: String): Result<Unit>
}
