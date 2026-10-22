package coredevices.haversine

import android.content.Context
import gravel.ring.KableRingTransport
import gravel.ring.PlatformHooks
import gravel.ring.sync.RingEngine
import gravel.ring.sync.liveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

actual class KMPHaversineSatelliteManager actual constructor() {
    private var engine: RingEngine = RingEngine.inactive(EmptyIndexStorage)

    constructor(
        pairedSatelliteIdProvider: () -> String?,
        debugDelegate: KMPHaversineDebugDelegate,
        hacksDelegate: KMPHaversineHacksDelegate,
        collectionIndexStorage: CollectionIndexStorage,
        context: Context,
        hwVersion: Pair<Int, Int>,
        scope: CoroutineScope,
    ) : this() {
        engine = liveEngine(
            transport = KableRingTransport(PlatformHooks(context.applicationContext), scope),
            storage = collectionIndexStorage,
            hacks = hacksDelegate,
            debug = debugDelegate,
            pairedId = pairedSatelliteIdProvider,
            hwVersion = hwVersion,
        )
    }

    actual val lastRing: StateFlow<KMPHaversineSatellite?>
        get() = engine.lastRing

    actual suspend fun getSatelliteById(id: String): KMPHaversineSatellite? = engine.satellite(id)

    actual fun startScanning(): Flow<SatelliteStatus> = engine.startScanning()

    actual suspend fun awaitBluetoothReady() = engine.awaitBluetoothReady()

    actual fun restartPreemptiveTransfer() = engine.restartPreemptiveTransfer()

    actual suspend fun programSatelliteWithUserID(satelliteId: String, uid: String): Result<Unit> {
        if (satelliteId.isEmpty() || uid.isEmpty()) {
            return Result.failure(HaversineFeatureUnavailable("programSatelliteWithUserID"))
        }
        return engine.programUserId()
    }
}

private object EmptyIndexStorage : CollectionIndexStorage {
    override val lastSuccessfulCollectionIndex = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    override fun setLastSuccessfulCollectionIndex(index: Int?) = Unit
}
