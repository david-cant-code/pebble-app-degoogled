package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Source sentinel for Gravel's call sites in the upstream-owned
 * `CompanionAppLifecycleManager`, which no unit test can construct (Room and WebView
 * dependencies): in `handleNewRunningApp` the one-shot read of the Network grant and the
 * PebbleKit toggle and Network grant watcher launches, and in `createCompanionApps` the
 * `shouldRunPkjs` gate and the PebbleKit toggle session gate. A sync merge that resolves any of
 * these to upstream's text removes the call, which the called functions' own tests cannot notice.
 *
 * The checks match text with line comments removed. Each watcher's arguments are matched inside
 * that watcher's own call or block. Not checked: the session generation the watchers are given,
 * text inside a block comment, which would satisfy a check, and which function the matched text
 * sits in: every check but the watchers' arguments is satisfied by a match anywhere in the file.
 * [knownBadEditsAreNoticed] applies seven known-bad edits to the real source, which trip nine
 * of the seventeen checks.
 */
class CompanionSessionGateSentinelTest {

    private val manager =
        "libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/connection/endpointmanager/CompanionAppLifecycleManager.kt"

    private val source by lazy { TrackedTree.file(manager).readText() }

    private val networkPermission = """lockerEntry\s*\.\s*id\s*,\s*LockerAppPermissionType\s*\.\s*Network\s*\)"""
    private val restartCallback = Regex("""requestRestart\s*=\s*sessionCoordinator\s*::\s*requestRestart""")
    private val ownApp = Regex("""app\s*=\s*lockerEntry\s*\.\s*id\s*,""")

    private fun code(text: String) = text.lines().joinToString("\n") { it.substringBefore("//") }

    /** The text from the end of [head]'s first match to the bracket that closes it; [head] ends on the opening one. */
    private fun enclosedBy(text: String, head: Regex): String? {
        val start = (head.find(text) ?: return null).range.last
        val (open, close) = if (text[start] == '(') '(' to ')' else '{' to '}'
        var depth = 0
        for (i in start until text.length) {
            if (text[i] == open) depth++
            if (text[i] == close && --depth == 0) return text.substring(start + 1, i)
        }
        return null
    }

    /** The message of every check that [text] fails. */
    private fun problems(text: String): List<String> = buildList {
        val code = code(text)
        fun check(holds: Boolean, message: String) {
            if (!holds) add(message)
        }

        check(
            Regex("""val\s+networkGranted\s*=\s*watchappPermissions\s*\.\s*isWatchappPermissionGranted\(\s*$networkPermission""")
                .containsMatchIn(code),
            "handleNewRunningApp no longer reads the app's Network grant once for the session",
        )
        check(
            Regex("""createCompanionApps\(\s*pbw\s*,\s*lockerEntry\s*,\s*watchConfig\s*,\s*networkGranted\s*,?\s*\)""").containsMatchIn(code),
            "createCompanionApps no longer builds the session from the config snapshot and the grant read",
        )

        val toggleWatcher = enclosedBy(code, Regex("""activeAppScope\s*\.\s*launchPlatformSessionGateWatcher\(""")).orEmpty()
        check(toggleWatcher.isNotEmpty(), "handleNewRunningApp no longer launches the PebbleKit toggle watcher on the session scope")
        check(
            Regex("""builtWith\s*=\s*watchConfig\s*\.\s*allowsPlatformCompanionSession\(""").containsMatchIn(toggleWatcher),
            "the toggle watcher's baseline no longer comes from the config snapshot the session was built from",
        )
        check(ownApp.containsMatchIn(toggleWatcher), "the toggle watcher no longer names the session's own app")
        check(restartCallback.containsMatchIn(toggleWatcher), "the toggle watcher no longer asks the session coordinator for the restart")

        val grantWatcherBlock = enclosedBy(code, Regex("""if\s*\(\s*pbw\s*\.\s*hasPKJS\s*\)\s*\{""")).orEmpty()
        val grantWatcher = enclosedBy(grantWatcherBlock, Regex("""activeAppScope\s*\.\s*launchNetworkGrantWatcher\(""")).orEmpty()
        check(
            grantWatcher.isNotEmpty(),
            "handleNewRunningApp no longer launches the Network grant watcher on the session scope " +
                "for an app with a PebbleKit JS side",
        )
        check(
            Regex("""watchappPermissionGranted\(\s*$networkPermission""").containsMatchIn(grantWatcherBlock),
            "the grant watcher no longer watches the app's Network grant",
        )
        check(
            Regex("""builtWith\s*=\s*networkGranted\s*,""").containsMatchIn(grantWatcher),
            "the grant watcher's baseline is no longer the grant the session was built with",
        )
        check(ownApp.containsMatchIn(grantWatcher), "the grant watcher no longer names the session's own app")
        check(restartCallback.containsMatchIn(grantWatcher), "the grant watcher no longer asks the session coordinator for the restart")
        check(!code.contains("denyToAllowTransitions"), "upstream sync brought back the one-direction restart")

        check(
            Regex(
                """val\s+runPkjs\s*=\s*shouldRunPkjs\(\s*pbw\s*\.\s*hasPKJS\s*,\s*networkGranted\s*,\s*networkDenyEnforcement\s*\.\s*primaryLayerActive\s*\)""",
            ).containsMatchIn(code),
            "createCompanionApps no longer consults shouldRunPkjs",
        )
        check(
            Regex("""val\s+pkjsApp\s*=\s*if\s*\(\s*runPkjs\s*\)""").containsMatchIn(code),
            "the PebbleKit JS session is no longer created under shouldRunPkjs's answer",
        )
        check(
            Regex("""pkjsApp\s*\?\.\s*let\s*\{\s*add\(\s*it\s*\)\s*\}""").containsMatchIn(code),
            "createCompanionApps no longer returns the PebbleKit JS session it created",
        )
        // On the snapshot parameter itself: `x.watchConfig.allowsPlatformCompanionSession(` does not match.
        check(
            Regex(
                """(?<![\w.])watchConfig\s*\.\s*allowsPlatformCompanionSession\(\s*pbw\s*\.\s*info\s*,\s*pkjsRunning\s*=\s*pkjsApp\s*!=\s*null\s*,?\s*\)""",
            ).containsMatchIn(code),
            "createCompanionApps no longer consults watchConfig.allowsPlatformCompanionSession " +
                "before creating the platform session",
        )
        check(
            !code.contains("appMessageToMultipleCompanions || pkjsApp == null"),
            "upstream's ungated session condition is back in createCompanionApps",
        )
    }

    @Test
    fun theManagerHoldsEveryGravelCallSite() {
        assertEquals(emptyList(), problems(source))
    }

    private fun String.edited(old: String, new: String): String {
        assertTrue(contains(old), "the manager no longer holds the text this edit replaces: $old")
        return replaceFirst(old, new)
    }

    private val grantWatcherBaseline = "builtWith = networkGranted,"
    private val toggleWatcherBaseline = "builtWith = watchConfig.allowsPlatformCompanionSession(pbw.info, pkjsRunning),"

    @Test
    fun knownBadEditsAreNoticed() {
        val edits: Map<String, (String) -> String> = mapOf(
            "the one-shot read takes the Location grant" to {
                it.edited(".isWatchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Network)", ".isWatchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Location)")
            },
            "the grant watcher watches the Location grant" to {
                it.edited(".watchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Network)", ".watchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Location)")
            },
            "the grant watcher names another app" to {
                val call = "activeAppScope.launchNetworkGrantWatcher("
                it.substringBefore(call) + call + it.substringAfter(call).edited("app = lockerEntry.id,", "app = Uuid.NIL,")
            },
            "the two watchers' baselines are swapped" to {
                it.edited(toggleWatcherBaseline, "builtWith = SWAP,")
                    .edited(grantWatcherBaseline, toggleWatcherBaseline)
                    .edited("builtWith = SWAP,", grantWatcherBaseline)
            },
            "the PebbleKit JS session is created and not returned" to {
                it.edited("pkjsApp?.let { add(it) }", "// pkjsApp?.let { add(it) }")
            },
            "the toggle watcher loses its restart callback" to {
                it.edited("requestRestart = sessionCoordinator::requestRestart,", "requestRestart = { _, _ -> },")
            },
            "the grant watcher launches for every app" to {
                it.edited("if (pbw.hasPKJS) {", "run {")
            },
        )
        for ((name, edit) in edits) {
            val edited = edit(source)
            assertNotEquals(source, edited, name)
            assertTrue(problems(edited).isNotEmpty(), "went unnoticed: $name")
        }
    }

    @Test
    fun harmlessRefactorsStayGreen() {
        val grantFlow = "grant = watchappPermissions\n                        .watchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Network),"
        val refactors: Map<String, (String) -> String> = mapOf(
            "the grant flow is hoisted into a local inside the block" to {
                it.edited(
                    "                activeAppScope.launchNetworkGrantWatcher(\n                    $grantFlow",
                    "                val grantFlow = watchappPermissions\n" +
                        "                    .watchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Network)\n" +
                        "                activeAppScope.launchNetworkGrantWatcher(\n                    grant = grantFlow,",
                )
            },
            "a third watcher reuses the restart callback" to {
                it.edited(
                    "            if (pbw.hasPKJS) {",
                    "            activeAppScope.launchAnotherWatcher(requestRestart = sessionCoordinator::requestRestart)\n            if (pbw.hasPKJS) {",
                )
            },
        )
        for ((name, refactor) in refactors) {
            val edited = refactor(source)
            assertNotEquals(source, edited, name)
            assertEquals(emptyList(), problems(edited), name)
        }
    }
}
