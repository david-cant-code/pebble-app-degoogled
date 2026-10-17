package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ui.NotebookWatchAppValues
import com.anopticlabs.gravel.ui.WatchAppStatus
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookWatchAppControlsTest {
    private val shipped = listOf(
        WatchAppRelease("0.1", "old", sizeBytes = 1, url = "https://example.invalid/0.1"),
        WatchAppRelease("0.2", "new", sizeBytes = 1, url = "https://example.invalid/0.2"),
    )
    private val locker = MutableSharedFlow<Boolean>(replay = 1)
    private var served: String? = null
    private var trusted: String? = null
    private var installResult = WatchAppInstallResult.INSTALLED
    private val installs = mutableListOf<WatchAppRelease>()
    private var removes = true
    private var removeCalls = 0
    private var trustSaves = true
    private var trustClears = true
    private var servedThrows = false
    private var connected = true

    private fun TestScope.controls(
        releases: List<WatchAppRelease> = shipped,
        installGate: CompletableDeferred<Unit>? = null,
        installThrows: Boolean = false,
    ) = NotebookWatchAppControls(
        releases = releases,
        lockerEntry = locker,
        servedSha256 = {
            check(!servedThrows) { "the cached copy is unreadable" }
            served
        },
        marked = { it == trusted },
        writeTrust = {
            if (trustSaves) trusted = it
            trustSaves
        },
        clearTrust = {
            if (trustClears) trusted = null
            trustClears
        },
        installRelease = { release ->
            installGate?.await()
            check(!installThrows) { "install broke" }
            installs += release
            if (installResult == WatchAppInstallResult.INSTALLED) {
                served = release.sha256
                locker.emit(true)
            }
            installResult
        },
        removeApp = {
            removeCalls++
            if (removes) {
                served = null
                locker.emit(false)
            }
            removes
        },
        watchConnected = { connected },
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
    )

    /** The controls once they have read a locker that has the copy [sha256], or no copy for null. */
    private suspend fun TestScope.started(sha256: String?, controls: NotebookWatchAppControls = controls()): NotebookWatchAppControls {
        served = sha256
        controls.values
        locker.emit(sha256 != null)
        runCurrent()
        return controls
    }

    @Test
    fun theStatusIsReadWhenTheLockerEntryIsKnownAndAgainWhenItChanges() = runTest {
        val controls = controls()
        assertNull(controls.values.value.status)
        assertEquals("0.2", controls.values.value.offered)
        locker.emit(false)
        runCurrent()
        assertEquals(WatchAppStatus.NOT_INSTALLED, controls.values.value.status)
        served = "old"
        locker.emit(true)
        runCurrent()
        assertEquals(WatchAppStatus.UPDATE_AVAILABLE, controls.values.value.status)
        assertEquals("0.1", controls.values.value.installedVersion)
        assertEquals("old", controls.values.value.sha256)
        served = "abc"
        locker.emit(true)
        runCurrent()
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        assertNull(controls.values.value.installedVersion)
        assertEquals("abc", controls.values.value.sha256)
    }

    @Test
    fun installingPutsTheNewestReleaseInTheLocker() = runTest {
        val controls = started(null)
        controls.install()
        runCurrent()
        assertEquals(listOf(shipped.last()), installs)
        val values = controls.values.value
        assertEquals(WatchAppStatus.INSTALLED, values.status)
        assertEquals("0.2", values.installedVersion)
        assertEquals(WatchAppLines.INSTALLED, values.message)
        assertFalse(values.busy)
    }

    @Test
    fun anInstallWithNoWatchConnectedSaysWhenItArrives() = runTest {
        connected = false
        val controls = started("old")
        controls.install()
        runCurrent()
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
        assertEquals(WatchAppLines.INSTALLED_NO_WATCH, controls.values.value.message)
    }

    @Test
    fun aFailedInstallSaysWhyAndChangesNothing() = runTest {
        val lines = mapOf(
            WatchAppInstallResult.DOWNLOAD_FAILED to WatchAppLines.DOWNLOAD_FAILED,
            WatchAppInstallResult.CHECK_FAILED to WatchAppLines.CHECK_FAILED,
            WatchAppInstallResult.INSTALL_FAILED to WatchAppLines.INSTALL_FAILED,
        )
        val controls = started(null)
        for ((result, line) in lines) {
            installResult = result
            controls.install()
            runCurrent()
            assertEquals(line, controls.values.value.message)
            assertEquals(WatchAppStatus.NOT_INSTALLED, controls.values.value.status)
            assertFalse(controls.values.value.busy)
        }
    }

    @Test
    fun anInstallThatThrowsEndsAsAFailedInstall() = runTest {
        val controls = started(null, controls(installThrows = true))
        controls.install()
        runCurrent()
        assertEquals(WatchAppLines.INSTALL_FAILED, controls.values.value.message)
        assertFalse(controls.values.value.busy)
        assertTrue(installs.isEmpty())
    }

    @Test
    fun whileOneActionRunsTheOthersAreIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controls = started("abc", controls(installGate = gate))
        controls.install()
        runCurrent()
        assertTrue(controls.values.value.busy)
        assertNull(controls.values.value.message)
        controls.install()
        controls.remove()
        controls.trustCopy("abc")
        controls.stopTrusting()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, installs.size)
        assertEquals(0, removeCalls)
        assertEquals("new", served)
        assertNull(trusted)
        assertFalse(controls.values.value.busy)
    }

    @Test
    fun theLockerEntryIsFollowedOnceHoweverOftenTheValuesAreRead() = runTest {
        val controls = started("new")
        controls.values
        controls.values
        runCurrent()
        assertEquals(1, locker.subscriptionCount.value)
    }

    @Test
    fun aCopyWhoseDigestCannotBeReadHasNoDigestAndTheEntryIsStillFollowed() = runTest {
        servedThrows = true
        val controls = started("abc")
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        assertNull(controls.values.value.sha256)
        controls.trustCopy("abc")
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppLines.COPY_CHANGED, controls.values.value.message)
        servedThrows = false
        locker.emit(true)
        runCurrent()
        assertEquals("abc", controls.values.value.sha256)
    }

    @Test
    fun rereadingShowsACopyThatChangedWithNoChangeToTheLockerEntry() = runTest {
        val controls = started("abc")
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        served = "new"
        controls.reread()
        runCurrent()
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
        assertEquals("new", controls.values.value.sha256)
    }

    @Test
    fun aResultIsPutAwayOnceItWasShown() = runTest {
        val controls = started(null)
        controls.install()
        runCurrent()
        controls.resultShown(WatchAppLines.REMOVED)
        assertEquals(WatchAppLines.INSTALLED, controls.values.value.message)
        controls.resultShown(WatchAppLines.INSTALLED)
        assertNull(controls.values.value.message)
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
    }

    @Test
    fun withNoReleaseToInstallNothingIsOfferedOrInstalled() = runTest {
        val controls = started(null, controls(releases = emptyList()))
        assertNull(controls.values.value.offered)
        controls.install()
        runCurrent()
        assertTrue(installs.isEmpty())
        assertFalse(controls.values.value.busy)
        assertNull(controls.values.value.message)
    }

    @Test
    fun trustingMarksTheCopyTheUserWasAskedAbout() = runTest {
        val controls = started("abc")
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        controls.trustCopy("abc")
        runCurrent()
        assertEquals("abc", trusted)
        assertEquals(WatchAppStatus.USER_TRUSTED, controls.values.value.status)
        assertEquals(WatchAppLines.TRUSTED, controls.values.value.message)
        trustClears = false
        controls.stopTrusting()
        runCurrent()
        assertEquals("abc", trusted)
        assertEquals(WatchAppStatus.USER_TRUSTED, controls.values.value.status)
        assertEquals(WatchAppLines.TRUST_FAILED, controls.values.value.message)
        trustClears = true
        controls.stopTrusting()
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        assertEquals(WatchAppLines.TRUST_ENDED, controls.values.value.message)
    }

    @Test
    fun aRemovalOrAnInstallEndsTheMark() = runTest {
        val controls = started("abc")
        controls.trustCopy("abc")
        runCurrent()
        assertEquals("abc", trusted)
        controls.remove()
        runCurrent()
        assertNull(trusted)
        served = "abc"
        locker.emit(true)
        runCurrent()
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        controls.trustCopy("abc")
        runCurrent()
        assertEquals(WatchAppStatus.USER_TRUSTED, controls.values.value.status)
        controls.install()
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
    }

    @Test
    fun aCopyThatChangedSinceTheUserWasAskedIsNotTrusted() = runTest {
        val controls = started("abc")
        served = "abd"
        controls.trustCopy("abc")
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppLines.COPY_CHANGED, controls.values.value.message)
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
        served = null
        controls.trustCopy("abd")
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppLines.COPY_CHANGED, controls.values.value.message)
    }

    @Test
    fun aTrustThatCannotBeSavedSaysSo() = runTest {
        trustSaves = false
        val controls = started("abc")
        controls.trustCopy("abc")
        runCurrent()
        assertNull(trusted)
        assertEquals(WatchAppLines.TRUST_FAILED, controls.values.value.message)
        assertEquals(WatchAppStatus.UNVERIFIED, controls.values.value.status)
    }

    @Test
    fun removingTakesTheCopyOutOfTheLocker() = runTest {
        val controls = started("new")
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
        removes = false
        controls.remove()
        runCurrent()
        assertEquals(WatchAppLines.REMOVE_FAILED, controls.values.value.message)
        assertEquals(WatchAppStatus.INSTALLED, controls.values.value.status)
        removes = true
        controls.remove()
        runCurrent()
        assertEquals(WatchAppLines.REMOVED, controls.values.value.message)
        assertEquals(WatchAppStatus.NOT_INSTALLED, controls.values.value.status)
        assertNull(controls.values.value.sha256)
    }

    @Test
    fun theUpdateLineNamesANewerVersionUntilItIsPutAway() {
        val update = NotebookWatchAppValues(status = WatchAppStatus.UPDATE_AVAILABLE, offered = "0.2", installedVersion = "0.1")
        assertEquals("0.2", watchAppUpdateToAnnounce(update, dismissed = ""))
        assertEquals("0.2", watchAppUpdateToAnnounce(update, dismissed = "0.1"))
        assertNull(watchAppUpdateToAnnounce(update, dismissed = "0.2"))
        assertNull(watchAppUpdateToAnnounce(update, dismissed = null))
        for (status in WatchAppStatus.entries - WatchAppStatus.UPDATE_AVAILABLE) {
            assertNull(watchAppUpdateToAnnounce(update.copy(status = status), dismissed = ""), "$status")
        }
        assertNull(watchAppUpdateToAnnounce(update.copy(status = null), dismissed = ""))
    }

    @Test
    fun theQuickLaunchButtonsOfAnAppAreTheOnesSetToIt() {
        val app = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")
        val other = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val prefs: List<WatchPreference<*>> = listOf(
            WatchPreference(QuicklaunchWatchPref.QlUp, QuickLaunchSetting(enabled = true, uuid = app)),
            WatchPreference(QuicklaunchWatchPref.QlDown, QuickLaunchSetting(enabled = true, uuid = other)),
            WatchPreference(QuicklaunchWatchPref.QlSelect, null),
            WatchPreference(QuicklaunchWatchPref.QlBack, QuickLaunchSetting(enabled = false, uuid = app)),
        )
        assertEquals(listOf(QuicklaunchWatchPref.QlUp, QuicklaunchWatchPref.QlBack), quickLaunchButtonsFor(prefs, app))
        assertTrue(quickLaunchButtonsFor(prefs, Uuid.parse("99999999-2222-3333-4444-555555555555")).isEmpty())
    }

    @Test
    fun removingTheAppTurnsOffTheButtonsThatOpenedItAndNoOthers() = runTest {
        val app = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")
        val other = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val libPebble = FakeLibPebble()
        libPebble.setWatchPref(WatchPreference(QuicklaunchWatchPref.QlUp, QuickLaunchSetting(enabled = true, uuid = app)))
        libPebble.setWatchPref(WatchPreference(QuicklaunchWatchPref.QlDown, QuickLaunchSetting(enabled = true, uuid = other)))
        assertTrue(removeNotebookWatchApp(libPebble, app))
        val prefs = libPebble.watchPrefs.first()
        assertEquals(QuickLaunchSetting(enabled = false, uuid = null), prefs.first { it.pref == QuicklaunchWatchPref.QlUp }.value)
        assertEquals(QuickLaunchSetting(enabled = true, uuid = other), prefs.first { it.pref == QuicklaunchWatchPref.QlDown }.value)
        assertNull(prefs.first { it.pref == QuicklaunchWatchPref.QlSelect }.value)
    }
}
