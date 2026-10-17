package com.anopticlabs.gravel.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class NotebookWatchAppTextTest {
    /** A copy in the locker has the digest "abc" unless [sha256] says it could not be read. */
    private fun values(
        status: WatchAppStatus?,
        offered: String? = "0.2",
        installed: String? = null,
        busy: Boolean = false,
        sha256: String? = "abc".takeIf { status != null && status != WatchAppStatus.NOT_INSTALLED },
    ) = NotebookWatchAppValues(status = status, offered = offered, offeredBytes = offered?.let { 47_938 }, installedVersion = installed, sha256 = sha256, busy = busy)

    @Test
    fun theStatusLineSaysWhatIsInTheLocker() {
        assertNull(watchAppStatusLine(values(null)))
        assertEquals("Not installed", watchAppStatusLine(values(WatchAppStatus.NOT_INSTALLED)))
        assertNull(watchAppStatusLine(values(WatchAppStatus.NOT_INSTALLED, offered = null)))
        assertEquals("Version 0.2 is installed", watchAppStatusLine(values(WatchAppStatus.INSTALLED, installed = "0.2")))
        assertEquals(
            "Version 0.1 is installed. Version 0.2 is available.",
            watchAppStatusLine(values(WatchAppStatus.UPDATE_AVAILABLE, installed = "0.1")),
        )
        assertEquals("A copy you marked as trusted is installed", watchAppStatusLine(values(WatchAppStatus.USER_TRUSTED)))
        assertEquals(WatchAppText.UNVERIFIED, watchAppStatusLine(values(WatchAppStatus.UNVERIFIED)))
        assertEquals(WatchAppText.UNVERIFIED, watchAppStatusLine(values(WatchAppStatus.UNVERIFIED, offered = null)))
        assertEquals(WatchAppText.UNCHECKED, watchAppStatusLine(values(WatchAppStatus.UNVERIFIED, sha256 = null)))
    }

    @Test
    fun onlyAnUnverifiedCopyWhoseDigestWasReadCanBeTrusted() {
        assertTrue(watchAppCanBeTrusted(values(WatchAppStatus.UNVERIFIED)))
        assertFalse(watchAppCanBeTrusted(values(WatchAppStatus.UNVERIFIED, sha256 = null)))
        for (status in WatchAppStatus.entries - WatchAppStatus.UNVERIFIED) assertFalse(watchAppCanBeTrusted(values(status)), "$status")
        assertFalse(watchAppCanBeTrusted(values(null)))
    }

    @Test
    fun anInstallIsOfferedOnlyWhenItWouldChangeTheCopy() {
        assertEquals(WatchAppOffer.INSTALL, watchAppOffer(values(WatchAppStatus.NOT_INSTALLED)))
        assertEquals(WatchAppOffer.UPDATE, watchAppOffer(values(WatchAppStatus.UPDATE_AVAILABLE, installed = "0.1")))
        assertEquals(WatchAppOffer.REPLACE, watchAppOffer(values(WatchAppStatus.UNVERIFIED)))
        assertEquals(WatchAppOffer.REPLACE, watchAppOffer(values(WatchAppStatus.USER_TRUSTED)))
        assertNull(watchAppOffer(values(WatchAppStatus.INSTALLED, installed = "0.2")))
        assertNull(watchAppOffer(values(null)))
        for (status in WatchAppStatus.entries) {
            assertNull(watchAppOffer(values(status, offered = null)), "$status with no release")
            assertNull(watchAppOffer(values(status, busy = true)), "$status while busy")
        }
    }

    @Test
    fun theSettingsRowJoinsTheStatusAndTheOfferAsSentences() {
        assertEquals("Not installed. Tap to install it.", watchAppRowDescription(values(WatchAppStatus.NOT_INSTALLED)))
        assertEquals("Version 0.2 is installed", watchAppRowDescription(values(WatchAppStatus.INSTALLED, installed = "0.2")))
        assertEquals(
            "Version 0.1 is installed. Version 0.2 is available. Tap to update.",
            watchAppRowDescription(values(WatchAppStatus.UPDATE_AVAILABLE, installed = "0.1")),
        )
        assertEquals(
            "A copy you marked as trusted is installed. Tap to replace it with Gravel's copy.",
            watchAppRowDescription(values(WatchAppStatus.USER_TRUSTED)),
        )
        assertEquals("A copy you marked as trusted is installed", watchAppRowDescription(values(WatchAppStatus.USER_TRUSTED, offered = null)))
        assertEquals("Not installed", watchAppRowDescription(values(WatchAppStatus.NOT_INSTALLED), offer = null))
    }

    @Test
    fun theSettingsRowNamesTrustThisCopyWithoutSayingWhereItIs() {
        val own = "If you installed your own build or a modified one intentionally, you can mark it as trusted with Trust This Copy"
        assertEquals("${WatchAppText.UNVERIFIED} $own.", watchAppRowDescription(values(WatchAppStatus.UNVERIFIED, offered = null)))
        assertEquals(
            "${WatchAppText.UNVERIFIED} $own. Tap to replace it with Gravel's copy.",
            watchAppRowDescription(values(WatchAppStatus.UNVERIFIED)),
        )
        assertEquals("$own in Settings > Notebook.", WatchAppText.OWN_COPY)
    }

    @Test
    fun aCopyThatCouldNotBeCheckedIsNotPointedAtTrustThisCopy() {
        assertEquals(WatchAppText.UNCHECKED, watchAppRowDescription(values(WatchAppStatus.UNVERIFIED, offered = null, sha256 = null)))
        assertEquals(
            "${WatchAppText.UNCHECKED} Tap to replace it with Gravel's copy.",
            watchAppRowDescription(values(WatchAppStatus.UNVERIFIED, sha256 = null)),
        )
    }

    @Test
    fun aWatchButtonCanBeSetOnlyWithSettingsSyncOnAndAConnectedWatchThatTakesSettings() {
        val takes = setOf(ProtocolCapsFlag.SupportsBlobDbVersion)
        val doesNot = ProtocolCapsFlag.entries.toSet() - ProtocolCapsFlag.SupportsBlobDbVersion
        assertTrue(watchButtonsCanBeSet(listOf(takes), settingsSync = true))
        assertTrue(watchButtonsCanBeSet(listOf(doesNot, takes), settingsSync = true))
        assertFalse(watchButtonsCanBeSet(listOf(takes), settingsSync = false))
        assertFalse(watchButtonsCanBeSet(listOf(doesNot), settingsSync = true))
        assertFalse(watchButtonsCanBeSet(emptyList(), settingsSync = true))
    }

    @Test
    fun aCopyIsInTheLockerForEveryStatusButNotInstalledAndUnread() {
        assertFalse(watchAppInLocker(values(null)))
        assertFalse(watchAppInLocker(values(WatchAppStatus.NOT_INSTALLED)))
        for (status in WatchAppStatus.entries - WatchAppStatus.NOT_INSTALLED) assertTrue(watchAppInLocker(values(status)), "$status")
    }

    @Test
    fun theDownloadLineGivesTheSizeInKilobytes() {
        assertEquals("Gravel downloads the watch app (48 KB) from GitHub and checks it before installing.", WatchAppText.download(47_938))
        assertEquals("Gravel downloads the watch app (1 KB) from GitHub and checks it before installing.", WatchAppText.download(500))
    }

    @Test
    fun aDigestIsShownWholeInFoursFourGroupsToALine() {
        assertEquals(
            "0123 4567 89ab cdef\nfedc ba98 7654 3210\n0123 4567 89ab cdef\nfedc ba98 7654 3210",
            digestInFours("0123456789abcdeffedcba9876543210".repeat(2)),
        )
        assertEquals("abc", digestInFours("abc"))
    }

    @Test
    fun thePickerShowsTheFourHoldButtonsWithWhatTheyOpen() {
        val app = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")
        val music = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val unknown = Uuid.parse("99999999-2222-3333-4444-555555555555")
        val quiet = QuicklaunchWatchPref.QlBack.defaultValue.uuid
        val names = mapOf(null to "None", app to "Notebook", music to "Music", quiet to "Quiet Time")
        val prefs: List<WatchPreference<*>> = listOf(
            WatchPreference(QuicklaunchWatchPref.QlUp, QuickLaunchSetting(enabled = true, uuid = app)),
            WatchPreference(QuicklaunchWatchPref.QlSelect, QuickLaunchSetting(enabled = true, uuid = unknown)),
            WatchPreference(QuicklaunchWatchPref.QlDown, QuickLaunchSetting(enabled = false, uuid = music)),
            WatchPreference(QuicklaunchWatchPref.QlComboBackUp, QuickLaunchSetting(enabled = true, uuid = music)),
        )
        assertEquals(
            listOf(
                QuickLaunchRow(QuicklaunchWatchPref.QlUp, "Hold Up", "Notebook", opensThisApp = true),
                QuickLaunchRow(QuicklaunchWatchPref.QlSelect, "Hold Select", "Another app", opensThisApp = false),
                QuickLaunchRow(QuicklaunchWatchPref.QlDown, "Hold Down", "None", opensThisApp = false),
                QuickLaunchRow(QuicklaunchWatchPref.QlBack, "Hold Back", "Quiet Time", opensThisApp = false),
            ),
            quickLaunchRows(prefs, names, app),
        )
        assertTrue(aHoldButtonOpens(prefs, app))
        assertFalse(aHoldButtonOpens(prefs, music), "a button that is off, and a combination that is not a hold button")
        assertFalse(aHoldButtonOpens(emptyList(), app))
    }
}
