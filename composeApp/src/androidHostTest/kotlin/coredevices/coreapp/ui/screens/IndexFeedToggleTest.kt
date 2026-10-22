package coredevices.coreapp.ui.screens

import com.russhwolf.settings.MapSettings
import coredevices.pebble.ui.WatchHomeNavTab
import coredevices.pebble.ui.resolveSelectedHomeTab
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.DeviceChoice
import coredevices.util.onboardingDeviceChoice
import coredevices.util.recordOnboardingDeviceChoice
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexFeedToggleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toggleShowsAndHidesTheIndexTab() {
        val shown = WatchHomeNavTab.navBarEntries(indexEnabled = true, healthEnabled = false, preferHealthTab = true)
        val hidden = WatchHomeNavTab.navBarEntries(indexEnabled = false, healthEnabled = false, preferHealthTab = true)
        assertTrue(WatchHomeNavTab.Index in shown)
        assertFalse(WatchHomeNavTab.Index in hidden)
    }

    @Test
    fun hidingTheFeedFallsBackToTheFirstTab() {
        val entries = WatchHomeNavTab.navBarEntries(indexEnabled = false, healthEnabled = true, preferHealthTab = true)
        assertEquals(WatchHomeNavTab.WatchFaces, resolveSelectedHomeTab(WatchHomeNavTab.Index, entries))
        assertEquals(WatchHomeNavTab.Settings, resolveSelectedHomeTab(WatchHomeNavTab.Settings, entries))
    }

    @Test
    fun indexOrBothTurnsTheFeedOnAndWatchDoesNot() {
        val settings = MapSettings()
        val holder = CoreConfigHolder(CoreConfig(), settings, json)
        settings.recordOnboardingDeviceChoice(DeviceChoice.Watch, holder::enableIndexFeed)
        assertEquals(DeviceChoice.Watch, settings.onboardingDeviceChoice())
        assertFalse(holder.config.value.enableIndex)
        settings.recordOnboardingDeviceChoice(DeviceChoice.Index01, holder::enableIndexFeed)
        assertTrue(holder.config.value.enableIndex)
        holder.update(holder.config.value.copy(indexPermissionsConfirmed = true))
        holder.update(holder.config.value.withIndexFeedEnabled(false))
        settings.recordOnboardingDeviceChoice(DeviceChoice.Both, holder::enableIndexFeed)
        assertTrue(holder.config.value.enableIndex)
        assertFalse(holder.config.value.indexPermissionsConfirmed)
        settings.recordOnboardingDeviceChoice(DeviceChoice.Watch, holder::enableIndexFeed)
        assertEquals(DeviceChoice.Watch, settings.onboardingDeviceChoice())
        assertTrue(holder.config.value.enableIndex)
    }

    @Test
    fun turningTheFeedOffClearsThePermissionConfirmationAndPersists() {
        val settings = MapSettings()
        val holder = CoreConfigHolder(CoreConfig(), settings, json)
        holder.update(holder.config.value.copy(indexPermissionsConfirmed = true))
        holder.enableIndexFeed()
        assertTrue(holder.config.value.enableIndex)
        assertTrue(holder.config.value.indexPermissionsConfirmed)
        val reloaded = CoreConfigHolder(CoreConfig(), settings, json)
        assertTrue(reloaded.config.value.enableIndex)
        holder.update(holder.config.value.withIndexFeedEnabled(false))
        assertFalse(holder.config.value.enableIndex)
        assertFalse(holder.config.value.indexPermissionsConfirmed)
        assertFalse(CoreConfigHolder(CoreConfig(), settings, json).config.value.enableIndex)
    }
}
