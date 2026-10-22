package gravel.ring.sync

import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineHacksDelegate
import gravel.ring.transport.RingTransport

internal fun liveEngine(
    transport: RingTransport,
    storage: CollectionIndexStorage,
    hacks: KMPHaversineHacksDelegate,
    debug: KMPHaversineDebugDelegate,
    pairedId: () -> String?,
    hwVersion: Pair<Int, Int>,
): RingEngine = RingEngine(
    transport = transport,
    storage = storage,
    hacks = hacks,
    debug = debug,
    pairedId = pairedId,
    hwVersion = hwVersion,
    active = true,
)
