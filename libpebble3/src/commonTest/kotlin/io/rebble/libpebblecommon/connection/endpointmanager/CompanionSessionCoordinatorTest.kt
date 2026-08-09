package io.rebble.libpebblecommon.connection.endpointmanager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Pins the fork's companion session decision model: watch-side app changes are
 * conflated to the newest state (no session churn for apps the watch has already
 * left), restart requests serialize with app changes and are dropped unless they
 * still target the exact session that raised them, and the deny-to-allow detector
 * fires only on that transition. CompanionAppLifecycleManager itself cannot be
 * constructed here (Room-backed dependencies), which is why the decisions live in
 * the coordinator with injected effects; these tests drive the real coordinator
 * with recording effects whose begin/end markers make any interleaving visible.
 */
class CompanionSessionCoordinatorTest {
    private val appA = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val appB = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val appC = Uuid.parse("00000000-0000-0000-0000-0000000000cc")

    private class Harness {
        val runningApp = MutableStateFlow<Uuid?>(null)
        val log = mutableListOf<String>()
        var currentApp: Uuid? = null

        /**
         * When set, startSession parks on it after its begin marker, keeping the
         * single consumer busy so later events queue behind it.
         */
        var startGate: CompletableDeferred<Unit>? = null

        val coordinator = CompanionSessionCoordinator(
            latestRunningApp = { runningApp.value },
            currentSessionApp = { currentApp },
            stopSession = {
                currentApp = null
                log += "stop"
            },
            startSession = { uuid ->
                // The real effect assigns currentEntry before its first suspension
                // point; mirror that so restart guards see the app mid-build.
                currentApp = uuid
                log += "start:$uuid:begin"
                startGate?.await()
                log += "start:$uuid:end"
            },
        )
    }

    private fun TestScope.harness(): Harness {
        val h = Harness()
        backgroundScope.launch { h.coordinator.run(h.runningApp) }
        return h
    }

    @Test
    fun appChangesStartAndStopSessions() = runTest {
        val h = harness()
        runCurrent()
        // The initial null state matches the initial processed state: no effect.
        assertEquals(emptyList(), h.log)

        h.runningApp.value = appA
        runCurrent()
        assertEquals(listOf("stop", "start:$appA:begin", "start:$appA:end"), h.log)

        h.runningApp.value = null
        runCurrent()
        assertEquals(listOf("stop", "start:$appA:begin", "start:$appA:end", "stop"), h.log)
    }

    @Test
    fun rapidAppChangesConflateToTheLatest() = runTest {
        val h = harness()
        h.startGate = CompletableDeferred()
        h.runningApp.value = appA
        runCurrent()
        assertEquals(listOf("stop", "start:$appA:begin"), h.log)

        // Two switches land while A's session is still being built.
        h.runningApp.value = appB
        runCurrent()
        h.runningApp.value = appC
        runCurrent()

        h.startGate!!.complete(Unit)
        h.startGate = null
        runCurrent()
        // B never gets a session: by the time its event is processed the watch has
        // already moved on, so building it would only delay C.
        assertEquals(
            listOf(
                "stop", "start:$appA:begin", "start:$appA:end",
                "stop", "start:$appC:begin", "start:$appC:end",
            ),
            h.log,
        )
    }

    @Test
    fun blipAwayAndBackKeepsTheSessionAlive() = runTest {
        val h = harness()
        h.startGate = CompletableDeferred()
        h.runningApp.value = appA
        runCurrent()

        // The watch blips away and back before A's session finishes building.
        h.runningApp.value = null
        runCurrent()
        h.runningApp.value = appA
        runCurrent()

        h.startGate!!.complete(Unit)
        h.startGate = null
        runCurrent()
        // The null is stale (the watch is back on A) and the second A equals the
        // last processed value; the surviving session must not be torn down.
        assertEquals(listOf("stop", "start:$appA:begin", "start:$appA:end"), h.log)
    }

    @Test
    fun restartRequestForTheLiveSessionRestartsIt() = runTest {
        val h = harness()
        h.runningApp.value = appA
        runCurrent()

        h.coordinator.requestRestart(appA, h.coordinator.currentGeneration)
        runCurrent()
        assertEquals(
            listOf(
                "stop", "start:$appA:begin", "start:$appA:end",
                "stop", "start:$appA:begin", "start:$appA:end",
            ),
            h.log,
        )
    }

    @Test
    fun restartRequestFromAReplacedSessionIsDropped() = runTest {
        val h = harness()
        h.runningApp.value = appA
        runCurrent()
        val firstSessionGeneration = h.coordinator.currentGeneration

        // The watch relaunches A: a fresh session of the same app.
        h.runningApp.value = null
        runCurrent()
        h.runningApp.value = appA
        runCurrent()
        val logAfterRelaunch = h.log.toList()

        // A request raised by the first session must not restart the second one;
        // the second session already started with the grant in place, and the app
        // uuid alone cannot tell the two sessions apart.
        h.coordinator.requestRestart(appA, firstSessionGeneration)
        runCurrent()
        assertEquals(logAfterRelaunch, h.log)
    }

    @Test
    fun restartRequestForADifferentAppIsDropped() = runTest {
        val h = harness()
        h.runningApp.value = appA
        runCurrent()
        val logAfterStart = h.log.toList()

        h.coordinator.requestRestart(appB, h.coordinator.currentGeneration)
        runCurrent()
        assertEquals(logAfterStart, h.log)
    }

    @Test
    fun restartRequestsSerializeWithAppChanges() = runTest {
        val h = harness()
        h.startGate = CompletableDeferred()
        h.runningApp.value = appA
        runCurrent()

        // A restart raised while the session is still being built must wait for
        // the build to finish; the begin/end markers would expose interleaving.
        h.coordinator.requestRestart(appA, h.coordinator.currentGeneration)
        runCurrent()
        assertEquals(listOf("stop", "start:$appA:begin"), h.log)

        h.startGate!!.complete(Unit)
        h.startGate = null
        runCurrent()
        assertEquals(
            listOf(
                "stop", "start:$appA:begin", "start:$appA:end",
                "stop", "start:$appA:begin", "start:$appA:end",
            ),
            h.log,
        )
    }

    @Test
    fun denyToAllowEmitsOnlyOnThatTransition() = runTest {
        assertEquals(
            0,
            flowOf(true, true, false, false).denyToAllowTransitions().toList().size,
            "allowed at load or revoked mid-session must not restart",
        )
        assertEquals(
            2,
            flowOf(false, true, true, false, true).denyToAllowTransitions().toList().size,
            "each denied-to-allowed flip restarts exactly once",
        )
    }
}
