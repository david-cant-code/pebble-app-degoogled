package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source sentinel for the two PebbleKit toggle call sites in the upstream-owned
 * `CompanionAppLifecycleManager`, which no unit test can construct (Room and WebView
 * dependencies): the session gate in `createCompanionApps` and the mid-session watcher launch in
 * `handleNewRunningApp`. A sync merge that resolves either to upstream's text removes the call,
 * which the called functions' own tests cannot notice. These checks match text only: they do
 * not check the watcher's arguments, and a commented-out copy of a call would satisfy them.
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
    fun theSessionLaunchesTheToggleWatcher() {
        assertTrue(
            Regex("""activeAppScope\s*\.\s*launchPlatformSessionGateWatcher\(""").containsMatchIn(source),
            "handleNewRunningApp no longer launches the PebbleKit toggle watcher on the session scope",
        )
    }
}
