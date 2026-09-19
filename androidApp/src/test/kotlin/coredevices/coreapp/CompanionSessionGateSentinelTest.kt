package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source sentinel for Gravel's call sites in the upstream-owned
 * `CompanionAppLifecycleManager`, which no unit test can construct (Room and WebView
 * dependencies): the PebbleKit toggle session gate in `createCompanionApps`, and the PebbleKit
 * toggle and Network grant watcher launches in `handleNewRunningApp`. A sync merge that resolves either to upstream's text removes the call,
 * which the called functions' own tests cannot notice. These checks match text only: they cover
 * the gate call, the snapshot the session and the watcher's baseline are built from and the
 * restart callback, not the session generation the watcher is given, and a commented-out copy
 * of a call would satisfy them.
 */
class CompanionSessionGateSentinelTest {

    private val manager =
        "libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/connection/endpointmanager/CompanionAppLifecycleManager.kt"

    private val source by lazy { TrackedTree.file(manager).readText() }

    @Test
    fun theSessionFactoryIsGatedOnThePebbleKitToggles() {
        // On the snapshot parameter itself: `x.watchConfig.allowsPlatformCompanionSession(` does not match.
        val gate = Regex(
            """(?<![\w.])watchConfig\s*\.\s*allowsPlatformCompanionSession\(\s*pbw\s*\.\s*info\s*,\s*pkjsRunning\s*=\s*pkjsApp\s*!=\s*null\s*,?\s*\)""",
        )
        assertTrue(
            gate.containsMatchIn(source),
            "createCompanionApps no longer consults watchConfig.allowsPlatformCompanionSession " +
                "before creating the platform session",
        )
        assertTrue(
            !source.contains("appMessageToMultipleCompanions || pkjsApp == null"),
            "upstream's ungated session condition is back in createCompanionApps",
        )
    }

    @Test
    fun oneConfigSnapshotBuildsTheSessionAndSeedsTheWatcher() {
        assertTrue(
            Regex("""createCompanionApps\(\s*pbw\s*,\s*lockerEntry\s*,\s*watchConfig\s*,?\s*\)""").containsMatchIn(source),
            "createCompanionApps no longer builds the session from the config snapshot",
        )
        assertTrue(
            Regex("""builtWith\s*=\s*watchConfig\s*\.\s*allowsPlatformCompanionSession\(""").containsMatchIn(source),
            "the toggle watcher's baseline no longer comes from the same config snapshot",
        )
        assertTrue(
            Regex("""requestRestart\s*=\s*sessionCoordinator\s*::\s*requestRestart""").containsMatchIn(source),
            "the toggle watcher no longer asks the session coordinator for the restart",
        )
    }

    @Test
    fun aPkjsAppsSessionLaunchesTheNetworkGrantWatcher() {
        val launch = Regex(
            """if\s*\(\s*pbw\s*\.\s*hasPKJS\s*\)\s*\{\s*activeAppScope\s*\.\s*launchNetworkGrantWatcher\(""",
        )
        assertTrue(
            launch.containsMatchIn(source),
            "handleNewRunningApp no longer launches the Network grant watcher on the session scope " +
                "for an app with a PebbleKit JS side",
        )
        assertTrue(
            Regex("""requestRestart\s*=\s*sessionCoordinator\s*::\s*requestRestart""").findAll(source).count() == 2,
            "the Network grant watcher no longer asks the session coordinator for the restart",
        )
        assertTrue(
            !source.contains("denyToAllowTransitions"),
            "upstream sync brought back the one-direction restart",
        )
    }

    @Test
    fun theSessionLaunchesTheToggleWatcher() {
        assertTrue(
            Regex("""activeAppScope\s*\.\s*launchPlatformSessionGateWatcher\(""").containsMatchIn(source),
            "handleNewRunningApp no longer launches the PebbleKit toggle watcher on the session scope",
        )
    }
}
