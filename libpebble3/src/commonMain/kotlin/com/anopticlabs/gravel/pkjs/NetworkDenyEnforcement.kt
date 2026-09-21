package com.anopticlabs.gravel.pkjs

/**
 * What the embedding app reports about the process-wide control that stands under a watchapp's
 * denied Network grant. libpebble3 does not know what that control is.
 */
interface NetworkDenyEnforcement {
    /** Fixed for the life of the process. */
    val primaryLayerActive: Boolean
}

class FixedNetworkDenyEnforcement(override val primaryLayerActive: Boolean) : NetworkDenyEnforcement

/** A Network-denied app's PebbleKit JS side runs only where the primary layer is active. */
fun shouldRunPkjs(hasPkjs: Boolean, networkGranted: Boolean, primaryLayerActive: Boolean): Boolean =
    hasPkjs && (networkGranted || primaryLayerActive)
