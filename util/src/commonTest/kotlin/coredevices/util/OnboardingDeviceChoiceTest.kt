package coredevices.util

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingDeviceChoiceTest {
    @Test
    fun choiceRoundTripsThroughSettings() {
        val settings = MapSettings()
        assertNull(settings.onboardingDeviceChoice())
        settings.setOnboardingDeviceChoice(DeviceChoice.Index01)
        assertEquals(DeviceChoice.Index01, settings.onboardingDeviceChoice())
        settings.setOnboardingDeviceChoice(DeviceChoice.Both)
        assertEquals(DeviceChoice.Both, settings.onboardingDeviceChoice())
        settings.setOnboardingDeviceChoice(DeviceChoice.Watch)
        assertEquals(DeviceChoice.Watch, settings.onboardingDeviceChoice())
    }

    @Test
    fun unknownStoredValueIsNoChoice() {
        val settings = MapSettings("gravel.onboarding.device" to "watch-and-ring")
        assertNull(settings.onboardingDeviceChoice())
    }

    @Test
    fun addIndexFollowsWhetherTheBuildHasARingClient() {
        assertFalse(showAddIndexButton(ringClientPresent = false))
        assertTrue(showAddIndexButton(ringClientPresent = true))
    }

    @Test
    fun onlyRingChoicesIncludeARing() {
        assertFalse(DeviceChoice.Watch.includesRing())
        assertTrue(DeviceChoice.Index01.includesRing())
        assertTrue(DeviceChoice.Both.includesRing())
    }

    @Test
    fun indexFeedTabFollowsTheToggle() {
        assertFalse(showIndexFeedTab(enableIndex = false))
        assertTrue(showIndexFeedTab(enableIndex = true))
        assertFalse(indexFeedToggleVisible(ringClientPresent = false))
        assertTrue(indexFeedToggleVisible(ringClientPresent = true))
        assertFalse(DeviceChoice.Watch.enablesIndexFeed())
        assertTrue(DeviceChoice.Index01.enablesIndexFeed())
        assertTrue(DeviceChoice.Both.enablesIndexFeed())
    }

    @Test
    fun pairingPromotesWatchOrUnsetToBoth() {
        assertEquals(DeviceChoice.Both, DeviceChoice.Watch.afterRingPaired())
        assertEquals(DeviceChoice.Both, null.afterRingPaired())
        assertEquals(DeviceChoice.Index01, DeviceChoice.Index01.afterRingPaired())
        assertEquals(DeviceChoice.Both, DeviceChoice.Both.afterRingPaired())
    }
}
