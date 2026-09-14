package io.rebble.libpebblecommon.pebblekit

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Fork: pins the mid-session restart trigger of the PebbleKit toggles: it follows the app's
 * own surface, it fires in both directions, an unrelated config save is silent, a flip that
 * landed before the watcher subscribed still fires, and the watcher requests the restart of the
 * session it was launched for.
 */
class PlatformSessionGateChangesTest {

    private val classicApp = appInfo(companion = null)
    private val pk2App = appInfo(
        CompanionApp(android = AndroidCompanionAppRoot(apps = listOf(AndroidCompanionAppInstance(pkg = "com.example.companion")))),
    )

    private fun config(classic: Boolean, pebbleKit2: Boolean = true, calendarPins: Boolean = true) =
        LibPebbleConfig(
            watchConfig = WatchConfig(
                classicPebbleKitEnabled = classic,
                pebbleKit2Enabled = pebbleKit2,
                calendarPins = calendarPins,
            ),
        )

    @Test
    fun firesInBothDirectionsAndNotForUnrelatedSaves() = runTest {
        val flow = MutableStateFlow(config(classic = true))
        var restarts = 0
        backgroundScope.launch {
            flow.platformSessionGateChanges(classicApp, pkjsRunning = true, builtWith = true).collect { restarts++ }
        }
        runCurrent()
        assertEquals(0, restarts, "restarted a session built under the current setting")

        flow.value = config(classic = true, calendarPins = false)
        runCurrent()
        assertEquals(0, restarts, "an unrelated config save restarted the session")

        flow.value = config(classic = false)
        runCurrent()
        assertEquals(1, restarts, "turning the app's surface off did not restart")

        flow.value = config(classic = true)
        runCurrent()
        // Back at the built-with decision: the operator emits only on a divergence.
        assertEquals(1, restarts, "returning to the built-with setting restarted")

        flow.value = config(classic = false)
        runCurrent()
        assertEquals(2, restarts, "a second divergence did not restart")
    }

    @Test
    fun aFlipBeforeSubscriptionStillFires() = runTest {
        // The session was built with classic off (no platform session); the toggle went on
        // during the build, before this watcher subscribed.
        val flow = MutableStateFlow(config(classic = true))
        var restarts = 0
        backgroundScope.launch {
            flow.platformSessionGateChanges(classicApp, pkjsRunning = true, builtWith = false).collect { restarts++ }
        }
        runCurrent()
        assertEquals(1, restarts, "a flip that landed during the session build was missed")
    }

    @Test
    fun followsTheAppsOwnSurface() = runTest {
        val flow = MutableStateFlow(config(classic = false, pebbleKit2 = true))
        var restarts = 0
        backgroundScope.launch {
            flow.platformSessionGateChanges(pk2App, pkjsRunning = false, builtWith = true).collect { restarts++ }
        }
        runCurrent()

        flow.value = config(classic = true, pebbleKit2 = true)
        runCurrent()
        assertEquals(0, restarts, "the classic toggle restarted a PebbleKit 2 app's session")

        flow.value = config(classic = true, pebbleKit2 = false)
        runCurrent()
        assertEquals(1, restarts, "the PebbleKit 2 toggle did not restart a PebbleKit 2 app's session")
    }

    @Test
    fun theWatcherRequestsARestartOfTheSessionItWasLaunchedFor() = runTest {
        val flow = MutableStateFlow(config(classic = true))
        val app = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
        val requests = mutableListOf<Pair<Uuid, Long>>()
        backgroundScope.launchPlatformSessionGateWatcher(
            config = flow,
            appInfo = classicApp,
            pkjsRunning = true,
            builtWith = true,
            app = app,
            sessionGeneration = 3L,
            requestRestart = { uuid, generation -> requests += uuid to generation },
        )
        runCurrent()

        flow.value = config(classic = true, calendarPins = false)
        runCurrent()
        assertEquals(emptyList(), requests, "a save that left the decision unchanged requested a restart")

        flow.value = config(classic = false)
        runCurrent()
        assertEquals(listOf(app to 3L), requests)
    }

    private fun appInfo(companion: CompanionApp?) = PbwAppInfo(
        uuid = "864369ab-1f37-4a2e-9243-dd6b21af9c14",
        shortName = "test",
        versionLabel = "1.0",
        resources = Resources(),
        companionApp = companion,
    )
}
