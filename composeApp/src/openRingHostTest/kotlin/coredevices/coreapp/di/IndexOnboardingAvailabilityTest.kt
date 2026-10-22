package coredevices.coreapp.di

import kotlin.test.Test
import kotlin.test.assertTrue

class IndexOnboardingAvailabilityTest {
    @Test
    fun openBuildOffersARing() {
        assertTrue(indexOnboardingAvailable())
    }
}
