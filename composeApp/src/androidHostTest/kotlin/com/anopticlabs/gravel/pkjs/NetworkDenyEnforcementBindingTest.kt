package com.anopticlabs.gravel.pkjs

import coredevices.coreapp.di.androidDefaultModule
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFalse

class NetworkDenyEnforcementBindingTest {
    // Both consumers look the binding up with getOrNull(), so a missing definition raises no
    // error: every Network-denied app just loses its PebbleKit JS side.
    @Test
    fun theAppModuleBindsTheEnforcement() {
        val koin = koinApplication { modules(androidDefaultModule) }.koin
        // No install has run on the host JVM, so there is no result and the layer is inactive.
        assertFalse(koin.get<NetworkDenyEnforcement>().primaryLayerActive)
    }
}
