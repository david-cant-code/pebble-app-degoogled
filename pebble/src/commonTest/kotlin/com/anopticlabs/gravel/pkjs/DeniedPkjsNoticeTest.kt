package com.anopticlabs.gravel.pkjs

import com.anopticlabs.gravel.pkjs.DeniedPkjsNotice.DoesNotRun
import com.anopticlabs.gravel.pkjs.DeniedPkjsNotice.DoesNotRunSwitchOff
import com.anopticlabs.gravel.pkjs.DeniedPkjsNotice.RunsWithoutPrimaryLayer
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeniedPkjsNoticeTest {
    // networkGranted, primaryLayerActive, switchMayRunDeniedPkjs, switchOn -> notice
    private val expected = listOf(
        listOf(false, false, false, false) to DoesNotRun,
        listOf(false, false, false, true) to DoesNotRun,
        listOf(false, false, true, false) to DoesNotRunSwitchOff,
        listOf(false, false, true, true) to RunsWithoutPrimaryLayer,
        listOf(false, true, false, false) to null,
        listOf(false, true, false, true) to null,
        listOf(false, true, true, false) to null,
        listOf(false, true, true, true) to null,
        listOf(true, false, false, false) to null,
        listOf(true, false, false, true) to null,
        listOf(true, false, true, false) to null,
        listOf(true, false, true, true) to null,
        listOf(true, true, false, false) to null,
        listOf(true, true, false, true) to null,
        listOf(true, true, true, false) to null,
        listOf(true, true, true, true) to null,
    )

    @Test
    fun theFullTable() {
        for ((input, notice) in expected) {
            val (networkGranted, primaryLayerActive, switchMayRun, switchOn) = input
            assertEquals(
                notice,
                deniedPkjsNotice(networkGranted, FixedNetworkDenyEnforcement(primaryLayerActive, switchMayRun), switchOn),
                "networkGranted=$networkGranted primaryLayerActive=$primaryLayerActive " +
                    "switchMayRunDeniedPkjs=$switchMayRun switchOn=$switchOn",
            )
        }
    }

    @Test
    fun theUiReadsTheAppsBindingOrTheFailClosedFallback() {
        assertSame(UnreportedNetworkDenyEnforcement, koinApplication {}.koin.networkDenyEnforcement())
        val bound = FixedNetworkDenyEnforcement(primaryLayerActive = false, switchMayRunDeniedPkjs = true)
        val koin = koinApplication { modules(module { single<NetworkDenyEnforcement> { bound } }) }.koin
        assertSame(bound, koin.networkDenyEnforcement())
    }
}
