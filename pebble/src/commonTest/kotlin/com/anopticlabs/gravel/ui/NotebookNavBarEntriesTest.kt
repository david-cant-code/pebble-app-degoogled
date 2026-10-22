package com.anopticlabs.gravel.ui

import coredevices.pebble.ui.WatchHomeNavTab
import coredevices.pebble.ui.WatchHomeNavTab.Health
import coredevices.pebble.ui.WatchHomeNavTab.Notebook
import coredevices.pebble.ui.WatchHomeNavTab.Notifications
import coredevices.pebble.ui.WatchHomeNavTab.Settings
import coredevices.pebble.ui.WatchHomeNavTab.WatchFaces
import coredevices.pebble.ui.WatchHomeNavTab.Watches
import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookNavBarEntriesTest {

    private fun entries(healthEnabled: Boolean, preferHealthTab: Boolean, notebookShown: Boolean) =
        WatchHomeNavTab.navBarEntries(
            indexEnabled = false,
            healthEnabled = healthEnabled,
            preferHealthTab = preferHealthTab,
            notebookShown = notebookShown,
        )

    @Test
    fun aShownNotebookSitsSecondAndHealthTakesTheSharedSlotWhenPreferred() {
        assertEquals(listOf(WatchFaces, Notebook, Watches, Health, Settings), entries(true, true, true))
    }

    @Test
    fun aShownNotebookLeavesNotificationsInTheSharedSlotUnlessHealthIsPreferred() {
        assertEquals(listOf(WatchFaces, Notebook, Watches, Notifications, Settings), entries(true, false, true))
    }

    @Test
    fun aShownNotebookWithoutHealthTrackingKeepsNotifications() {
        assertEquals(listOf(WatchFaces, Notebook, Watches, Notifications, Settings), entries(false, true, true))
    }

    @Test
    fun theTabLeftOutOfTheBarIsTheOneSettingsLinksTo() {
        fun displaced(health: Boolean, preferHealth: Boolean, notebook: Boolean) =
            WatchHomeNavTab.displacedTabs(indexEnabled = false, healthEnabled = health, preferHealthTab = preferHealth, notebookShown = notebook)
        assertEquals(listOf(Health), displaced(health = true, preferHealth = false, notebook = true))
        assertEquals(listOf(Notifications), displaced(health = true, preferHealth = true, notebook = true))
        assertEquals(emptyList(), displaced(health = false, preferHealth = true, notebook = true))
        assertEquals(emptyList(), displaced(health = true, preferHealth = false, notebook = false))
    }

    @Test
    fun indexAloneKeepsUpstreamsBar() {
        assertEquals(
            listOf(WatchFaces, WatchHomeNavTab.Index, Watches, Notifications, Settings),
            WatchHomeNavTab.navBarEntries(indexEnabled = true, healthEnabled = true, preferHealthTab = false, notebookShown = false),
        )
        assertEquals(
            listOf(Health),
            WatchHomeNavTab.displacedTabs(indexEnabled = true, healthEnabled = true, preferHealthTab = false, notebookShown = false),
        )
    }

    @Test
    fun aHiddenNotebookLeavesTheBarAsUpstreamBuildsIt() {
        assertEquals(listOf(WatchFaces, Watches, Health, Notifications, Settings), entries(true, false, false))
        assertEquals(listOf(WatchFaces, Watches, Health, Notifications, Settings), entries(true, true, false))
    }
}
