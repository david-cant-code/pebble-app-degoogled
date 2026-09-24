package com.anopticlabs.gravel.pkjs

import coredevices.coreapp.di.androidDefaultModule
import org.koin.core.annotation.KoinInternalApi
import kotlin.test.Test
import kotlin.test.assertTrue

class NetworkDenyEnforcementBindingTest {
    // Every consumer looks the binding up with getOrNull(), so a missing definition raises no
    // error: every Network-denied app just loses its PebbleKit JS side. The definition reads an
    // Android Context, which the host JVM cannot provide; UdpFilterEnforcementTest covers what
    // it computes.
    @OptIn(KoinInternalApi::class)
    @Test
    fun theAppModuleBindsTheEnforcement() {
        assertTrue(androidDefaultModule.mappings.values.any { it.beanDefinition.primaryType == NetworkDenyEnforcement::class })
    }
}
