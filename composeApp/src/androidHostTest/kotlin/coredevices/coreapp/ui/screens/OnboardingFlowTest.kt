package coredevices.coreapp.ui.screens

import com.russhwolf.settings.MapSettings
import coredevices.coreapp.di.indexOnboardingAvailable
import coredevices.util.DeviceChoice
import coredevices.util.onboardingDeviceChoice
import coredevices.util.setOnboardingDeviceChoice
import coredevices.util.enablesIndexFeed
import coredevices.util.indexFeedToggleVisible
import coredevices.util.showAddIndexButton
import coredevices.util.showIndexFeedTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingFlowTest {
    @Test
    fun visibleChoicesFollowWhetherThisBuildHasARing() {
        assertEquals(
            if (indexOnboardingAvailable()) DeviceChoice.entries else listOf(DeviceChoice.Watch),
            visibleDeviceChoices(indexOnboardingAvailable()),
        )
    }

    @Test
    fun stubBuildOffersOnlyAWatch() {
        assertEquals(listOf(DeviceChoice.Watch), visibleDeviceChoices(ringAvailable = false))
    }

    @Test
    fun openBuildOffersWatchIndexAndBoth() {
        assertEquals(DeviceChoice.entries, visibleDeviceChoices(ringAvailable = true))
    }

    @Test
    fun watchKeepsTheExistingStages() {
        assertEquals(OnboardingStage.Permissions, stageAfterDeviceChoice(DeviceChoice.Watch))
        assertEquals(OnboardingStage.WatchappPrivacy, stageAfterPermissions(DeviceChoice.Watch))
        assertEquals(OnboardingStage.Done, stageAfterWatchPrivacy(DeviceChoice.Watch))
    }

    @Test
    fun indexSkipsWatchSetup() {
        assertEquals(OnboardingStage.RingPermission, stageAfterDeviceChoice(DeviceChoice.Index01))
        assertEquals(OnboardingStage.Done, stageAfterWatchPrivacy(DeviceChoice.Index01))
    }

    @Test
    fun bothRunsWatchSetupThenTheRing() {
        assertEquals(OnboardingStage.Permissions, stageAfterDeviceChoice(DeviceChoice.Both))
        assertEquals(OnboardingStage.WatchappPrivacy, stageAfterPermissions(DeviceChoice.Both))
        assertEquals(OnboardingStage.RingSetup, stageAfterWatchPrivacy(DeviceChoice.Both))
    }

    @Test
    fun choiceRoundTripsAndGatesAddIndex() {
        val settings = MapSettings()
        assertNull(settings.onboardingDeviceChoice())
        assertFalse(showAddIndexButton(ringClientPresent = false))
        assertTrue(showAddIndexButton(ringClientPresent = true))
        settings.setOnboardingDeviceChoice(DeviceChoice.Watch)
        assertEquals(DeviceChoice.Watch, settings.onboardingDeviceChoice())
        assertTrue(showAddIndexButton(ringClientPresent = true))
        settings.setOnboardingDeviceChoice(DeviceChoice.Index01)
        assertTrue(showAddIndexButton(ringClientPresent = true))
        settings.setOnboardingDeviceChoice(DeviceChoice.Both)
        assertTrue(showAddIndexButton(ringClientPresent = true))
    }

    @Test
    fun indexFeedTabFollowsTheToggleAndTheRowFollowsTheBuild() {
        assertFalse(showIndexFeedTab(enableIndex = false))
        assertTrue(showIndexFeedTab(enableIndex = true))
        assertFalse(DeviceChoice.Watch.enablesIndexFeed())
        assertTrue(DeviceChoice.Index01.enablesIndexFeed())
        assertTrue(DeviceChoice.Both.enablesIndexFeed())
        assertEquals(indexOnboardingAvailable(), indexFeedToggleVisible(indexOnboardingAvailable()))
        assertFalse(indexFeedToggleVisible(ringClientPresent = false))
    }
}
