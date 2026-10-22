package coredevices.coreapp.di

import kotlin.test.Test
import kotlin.test.assertFalse

class IndexOnboardingAvailabilityTest {
    @Test
    fun stubBuildDoesNotOfferARing() {
        assertFalse(indexOnboardingAvailable())
    }
}
