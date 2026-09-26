package com.anopticlabs.gravel.pkjs

/**
 * What the embedding app reports about the process-wide control that stands under a watchapp's
 * denied Network grant. libpebble3 does not know what that control is.
 */
interface NetworkDenyEnforcement {
    /** Fixed for the life of the process. */
    val primaryLayerActive: Boolean

    /**
     * Whether, while the primary layer is not active, WatchConfig.deniedPkjsWithoutPrimaryLayer
     * may let a Network-denied app's PebbleKit JS side run. May change between reads.
     */
    val switchMayRunDeniedPkjs: Boolean get() = false
}

/** What libpebble3 assumes where the embedding app reports nothing: fails closed. */
object UnreportedNetworkDenyEnforcement : NetworkDenyEnforcement {
    override val primaryLayerActive = false
}

class FixedNetworkDenyEnforcement(
    override val primaryLayerActive: Boolean,
    override val switchMayRunDeniedPkjs: Boolean = false,
) : NetworkDenyEnforcement

/** Whether WatchConfig.deniedPkjsWithoutPrimaryLayer decides anything in this process. */
val NetworkDenyEnforcement.deniedPkjsSwitchApplies: Boolean
    get() = !primaryLayerActive && switchMayRunDeniedPkjs

/** [switchOn] is WatchConfig.deniedPkjsWithoutPrimaryLayer. */
fun shouldRunPkjs(
    hasPkjs: Boolean,
    networkGranted: Boolean,
    enforcement: NetworkDenyEnforcement,
    switchOn: Boolean,
): Boolean = hasPkjs &&
    (networkGranted || enforcement.primaryLayerActive || (enforcement.deniedPkjsSwitchApplies && switchOn))
