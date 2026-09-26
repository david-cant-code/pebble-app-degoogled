package com.anopticlabs.gravel.pkjs

import org.koin.core.Koin

/** The app's binding, or what LibPebble3 gets without one (watchModule). */
fun Koin.networkDenyEnforcement(): NetworkDenyEnforcement =
    getOrNull<NetworkDenyEnforcement>() ?: UnreportedNetworkDenyEnforcement

/** What an app's permission controls say about its PebbleKit JS side while its Network grant is denied. */
enum class DeniedPkjsNotice {
    RunsWithoutPrimaryLayer,
    DoesNotRunSwitchOff,

    /** The switch does not apply. */
    DoesNotRun,
}

fun deniedPkjsNotice(networkGranted: Boolean, enforcement: NetworkDenyEnforcement, switchOn: Boolean): DeniedPkjsNotice? =
    when {
        networkGranted || enforcement.primaryLayerActive -> null
        shouldRunPkjs(hasPkjs = true, networkGranted = false, enforcement, switchOn) -> DeniedPkjsNotice.RunsWithoutPrimaryLayer
        enforcement.deniedPkjsSwitchApplies -> DeniedPkjsNotice.DoesNotRunSwitchOff
        else -> DeniedPkjsNotice.DoesNotRun
    }
