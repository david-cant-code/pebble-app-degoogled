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

/**
 * Whether an app's PebbleKit JS side gets a session: a denied Network grant is only honored
 * by running the script when the primary layer is there to enforce it.
 */
fun shouldRunPkjs(hasPkjs: Boolean, networkGranted: Boolean, primaryLayerActive: Boolean): Boolean =
    hasPkjs && (networkGranted || primaryLayerActive)
