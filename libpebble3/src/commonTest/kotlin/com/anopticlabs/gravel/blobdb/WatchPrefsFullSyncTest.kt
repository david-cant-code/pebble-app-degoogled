package com.anopticlabs.gravel.blobdb

import com.russhwolf.settings.MapSettings
import coredev.BlobDatabase
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.RequestedPrefSyncDone
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.SyncedWatchPrefKeys
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.recordSyncedWatchPrefKeys
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.syncedWatchPrefKeys
import io.rebble.libpebblecommon.database.entity.WatchPref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPrefsFullSyncTest {
    private val current = setOf("a", "b", "c")
    private val noKeys = SyncedWatchPrefKeys(emptyMap())

    @Test
    fun aWatchWithNoRecordIsNotCovered() {
        assertFalse(noKeys.covers("w1", current))
        assertFalse(noKeys.withDevice("w2", current).covers("w1", current))
    }

    @Test
    fun aWatchIsNotCoveredOnceThePhoneKnowsAKeyItDidNotThen() {
        assertFalse(noKeys.withDevice("w1", setOf("a", "b")).covers("w1", current))
    }

    @Test
    fun aWatchRecordedWithEveryCurrentKeyIsCovered() {
        assertTrue(noKeys.withDevice("w1", current).covers("w1", current))
        assertTrue(noKeys.withDevice("w1", current + "gone").covers("w1", current))
    }

    @Test
    fun theRecordSurvivesSettingsAndKeepsOtherWatches() {
        val settings = MapSettings()
        assertFalse(settings.syncedWatchPrefKeys().covers("w1", current))
        settings.recordSyncedWatchPrefKeys("w1", setOf("a"))
        settings.recordSyncedWatchPrefKeys("w2", current)
        settings.recordSyncedWatchPrefKeys("w1", current)
        assertEquals(mapOf("w1" to current, "w2" to current), settings.syncedWatchPrefKeys().keysByDevice)
    }

    @Test
    fun onlyTheFirstWatchPrefsSyncDoneAfterTheRequestCounts() {
        val done = RequestedPrefSyncDone(alreadySeen = 1)
        assertFalse(done.onSyncDone(BlobDatabase.WatchPrefs), "a SyncDone from before the request")
        assertFalse(done.onSyncDone(BlobDatabase.HealthParams))
        assertTrue(done.onSyncDone(BlobDatabase.WatchPrefs))
        assertFalse(done.onSyncDone(BlobDatabase.WatchPrefs), "reported twice")
        assertTrue(RequestedPrefSyncDone(alreadySeen = 0).onSyncDone(BlobDatabase.WatchPrefs))
    }

    @Test
    fun enumeratedKeysIncludeThePrefsNewlySynced() {
        val ids = WatchPref.enumeratePrefs().map { it.id }.toSet()
        listOf("dndWeekdaySchedule", "dndWeekendSchedule", "lightColorDayNightEnabled", "unitsWind", "systemTextSize").forEach {
            assertTrue(it in ids, "$it missing from the enumerated prefs")
        }
    }
}
