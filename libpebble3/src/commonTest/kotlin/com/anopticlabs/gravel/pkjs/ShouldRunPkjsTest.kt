package com.anopticlabs.gravel.pkjs

import io.rebble.libpebblecommon.WatchConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShouldRunPkjsTest {
    // networkGranted, primaryLayerActive, switchMayRunDeniedPkjs, switchOn -> runs, for an app with PebbleKit JS
    private val withPkjs = listOf(
        listOf(false, false, false, false) to false,
        listOf(false, false, false, true) to false,
        listOf(false, false, true, false) to false,
        listOf(false, false, true, true) to true,
        listOf(false, true, false, false) to true,
        listOf(false, true, false, true) to true,
        listOf(false, true, true, false) to true,
        listOf(false, true, true, true) to true,
        listOf(true, false, false, false) to true,
        listOf(true, false, false, true) to true,
        listOf(true, false, true, false) to true,
        listOf(true, false, true, true) to true,
        listOf(true, true, false, false) to true,
        listOf(true, true, false, true) to true,
        listOf(true, true, true, false) to true,
        listOf(true, true, true, true) to true,
    )

    @Test
    fun theFullTruthTable() {
        for ((input, runs) in withPkjs) {
            val (networkGranted, primaryLayerActive, switchMayRun, switchOn) = input
            val enforcement = FixedNetworkDenyEnforcement(primaryLayerActive, switchMayRun)
            val case = "networkGranted=$networkGranted primaryLayerActive=$primaryLayerActive " +
                "switchMayRunDeniedPkjs=$switchMayRun switchOn=$switchOn"
            assertEquals(runs, shouldRunPkjs(hasPkjs = true, networkGranted, enforcement, switchOn), case)
            assertFalse(shouldRunPkjs(hasPkjs = false, networkGranted, enforcement, switchOn), "no PebbleKit JS, $case")
        }
    }

    @Test
    fun theSwitchAppliesOnlyWhereThePrimaryLayerIsInactiveAndTheAppLetsIt() {
        assertFalse(FixedNetworkDenyEnforcement(primaryLayerActive = false).deniedPkjsSwitchApplies)
        assertTrue(FixedNetworkDenyEnforcement(primaryLayerActive = false, switchMayRunDeniedPkjs = true).deniedPkjsSwitchApplies)
        assertFalse(FixedNetworkDenyEnforcement(primaryLayerActive = true).deniedPkjsSwitchApplies)
        assertFalse(FixedNetworkDenyEnforcement(primaryLayerActive = true, switchMayRunDeniedPkjs = true).deniedPkjsSwitchApplies)
    }

    // An embedder that implements only primaryLayerActive runs no Network-denied session without it.
    @Test
    fun anEnforcementThatSaysNothingAboutTheSwitchFailsClosed() {
        val enforcement = object : NetworkDenyEnforcement {
            override val primaryLayerActive = false
        }
        assertFalse(enforcement.switchMayRunDeniedPkjs)
        assertFalse(shouldRunPkjs(hasPkjs = true, networkGranted = false, enforcement, switchOn = true))
        assertFalse(shouldRunPkjs(hasPkjs = true, networkGranted = false, UnreportedNetworkDenyEnforcement, switchOn = true))
    }

    // A config stored before the field existed decodes to the default, as an upgrade does.
    @Test
    fun theSwitchIsOnByDefault() {
        assertTrue(WatchConfig().deniedPkjsWithoutPrimaryLayer)
        val stored = Json { ignoreUnknownKeys = true }.decodeFromString(WatchConfig.serializer(), "{}")
        assertTrue(stored.deniedPkjsWithoutPrimaryLayer)
    }
}
