package io.rebble.libpebblecommon.connection.endpointmanager

import com.anopticlabs.gravel.pkjs.FixedNetworkDenyEnforcement
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
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
 * still target the exact session that raised them, and the Network grant detector
 * fires once per change of the grant, in either direction. CompanionAppLifecycleManager itself cannot be
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
    fun grantChangesEmitsOncePerFlipInEitherDirection() = runTest {
        assertEquals(0, flowOf(true).grantChanges(builtWith = true).toList().size, "the built-with value emitted")
        assertEquals(0, flowOf(false, false).grantChanges(builtWith = false).toList().size, "a repeated value emitted")
        assertEquals(1, flowOf(true, true, false, false).grantChanges(builtWith = true).toList().size, "a revocation must emit once")
        assertEquals(3, flowOf(false, true, true, false, true).grantChanges(builtWith = false).toList().size, "each flip must emit once")
        assertEquals(1, flowOf(true).grantChanges(builtWith = false).toList().size, "a flip before the first collection was missed")
    }

    @Test
    fun theNetworkGrantWatcherRequestsARestartOfItsSessionOnEachFlip() = runTest {
        val grant = MutableStateFlow(false)
        val requests = mutableListOf<Pair<Uuid, Long>>()
        val watcher = launchNetworkGrantWatcher(
            grant = grant,
            builtWith = false,
            app = appA,
            sessionGeneration = 3L,
            requestRestart = { uuid, generation -> requests += uuid to generation },
        )
        runCurrent()
        assertEquals(emptyList(), requests, "the grant the session started with requested a restart")

        grant.value = true
        runCurrent()
        assertEquals(listOf(appA to 3L), requests, "deny to allow did not request a restart")

        grant.value = false
        runCurrent()
        assertEquals(listOf(appA to 3L, appA to 3L), requests, "allow to deny did not request a restart")

        watcher.cancel()
        grant.value = true
        runCurrent()
        assertEquals(2, requests.size, "a cancelled watcher requested a restart")
    }

    private val switchApplies = FixedNetworkDenyEnforcement(primaryLayerActive = false, switchMayRunDeniedPkjs = true)

    private class Recorder {
        val requests = mutableListOf<Pair<Uuid, Long>>()
        val config = MutableStateFlow(LibPebbleConfig(watchConfig = WatchConfig(deniedPkjsWithoutPrimaryLayer = true)))

        fun write(change: (WatchConfig) -> WatchConfig) {
            config.value = config.value.copy(watchConfig = change(config.value.watchConfig))
        }

        fun request(app: Uuid, generation: Long) {
            requests += app to generation
        }
    }

    @Test
    fun theDeniedSessionSwitchWatcherRequestsARestartOnEachFlipOnly() = runTest {
        val r = Recorder()
        val watcher = launchDeniedPkjsSwitchWatcher(
            config = r.config,
            enforcement = switchApplies,
            builtWith = true,
            app = appA,
            sessionGeneration = 5L,
            requestRestart = r::request,
        )
        runCurrent()
        assertEquals(emptyList(), r.requests, "the value the session started with requested a restart")

        r.write { it.copy(classicPebbleKitEnabled = !it.classicPebbleKitEnabled) }
        runCurrent()
        assertEquals(emptyList(), r.requests, "an unrelated config write requested a restart")

        r.write { it.copy(deniedPkjsWithoutPrimaryLayer = false) }
        runCurrent()
        assertEquals(listOf(appA to 5L), r.requests, "turning the switch off did not request a restart")

        r.write { it.copy(deniedPkjsWithoutPrimaryLayer = true) }
        runCurrent()
        assertEquals(listOf(appA to 5L, appA to 5L), r.requests, "turning the switch on did not request a restart")

        watcher.cancel()
        r.write { it.copy(deniedPkjsWithoutPrimaryLayer = false) }
        runCurrent()
        assertEquals(2, r.requests.size, "a canceled watcher requested a restart")
    }

    // The switch flipped between the manager's build and the watcher's first collection.
    @Test
    fun theDeniedSessionSwitchWatcherComparesItsFirstValueWithTheBuild() = runTest {
        for (builtWith in listOf(true, false)) {
            val r = Recorder()
            r.write { it.copy(deniedPkjsWithoutPrimaryLayer = !builtWith) }
            val watcher = launchDeniedPkjsSwitchWatcher(
                config = r.config,
                enforcement = switchApplies,
                builtWith = builtWith,
                app = appA,
                sessionGeneration = 7L,
                requestRestart = r::request,
            )
            runCurrent()
            assertEquals(listOf(appA to 7L), r.requests, "a flip away from builtWith=$builtWith before the first collection")
            watcher.cancel()
        }
    }

    // A session built while the switch did not apply, which then starts to apply (a WebView update).
    @Test
    fun theDeniedSessionSwitchWatcherRestartsASessionOnceTheSwitchApplies() = runTest {
        val writesAfterTheSwitchApplies: Map<String, List<(WatchConfig) -> WatchConfig>> = mapOf(
            "an unrelated config write" to listOf { it.copy(classicPebbleKitEnabled = !it.classicPebbleKitEnabled) },
            "turning the switch off and on" to listOf(
                { it.copy(deniedPkjsWithoutPrimaryLayer = false) },
                { it.copy(deniedPkjsWithoutPrimaryLayer = true) },
            ),
        )
        for ((name, writes) in writesAfterTheSwitchApplies) {
            val enforcement = object : NetworkDenyEnforcement {
                override val primaryLayerActive = false
                override var switchMayRunDeniedPkjs = false
            }
            val r = Recorder()
            val watcher = launchDeniedPkjsSwitchWatcher(
                config = r.config,
                enforcement = enforcement,
                builtWith = false,
                app = appA,
                sessionGeneration = 9L,
                requestRestart = r::request,
            )
            runCurrent()
            r.write { it.copy(deniedPkjsWithoutPrimaryLayer = false) }
            runCurrent()
            r.write { it.copy(deniedPkjsWithoutPrimaryLayer = true) }
            runCurrent()
            assertEquals(emptyList(), r.requests, "a flip where the switch does not apply requested a restart ($name)")

            enforcement.switchMayRunDeniedPkjs = true
            runCurrent()
            assertEquals(emptyList(), r.requests, "the switch starting to apply requested a restart without a config write ($name)")
            writes.forEachIndexed { i, write ->
                r.write(write)
                runCurrent()
                val expected = if (i == writes.lastIndex) listOf(appA to 9L) else emptyList()
                assertEquals(expected, r.requests, "$name, write ${i + 1} of ${writes.size}")
            }
            watcher.cancel()
        }
    }

    @Test
    fun theDeniedSessionSwitchWatcherTreatsAReadThatThrowsAsNotRunning() = runTest {
        val enforcement = object : NetworkDenyEnforcement {
            var fails = false
            override val primaryLayerActive = false
            override val switchMayRunDeniedPkjs: Boolean
                get() = if (fails) throw IllegalStateException("WebView update service gone") else true
        }
        val r = Recorder()
        val watcher = launchDeniedPkjsSwitchWatcher(
            config = r.config,
            enforcement = enforcement,
            builtWith = true,
            app = appA,
            sessionGeneration = 13L,
            requestRestart = r::request,
        )
        runCurrent()
        enforcement.fails = true
        r.write { it.copy(classicPebbleKitEnabled = !it.classicPebbleKitEnabled) }
        runCurrent()
        assertEquals(listOf(appA to 13L), r.requests, "a read that threw did not request a restart")

        enforcement.fails = false
        r.write { it.copy(classicPebbleKitEnabled = !it.classicPebbleKitEnabled) }
        runCurrent()
        assertEquals(listOf(appA to 13L, appA to 13L), r.requests, "the watcher stopped after a read that threw")
        watcher.cancel()
    }

    // Where the primary layer is active, a Network-denied session runs its script whatever the switch says.
    @Test
    fun theDeniedSessionSwitchWatcherIgnoresTheSwitchUnderThePrimaryLayer() = runTest {
        val r = Recorder()
        val watcher = launchDeniedPkjsSwitchWatcher(
            config = r.config,
            enforcement = FixedNetworkDenyEnforcement(primaryLayerActive = true, switchMayRunDeniedPkjs = true),
            builtWith = true,
            app = appA,
            sessionGeneration = 11L,
            requestRestart = r::request,
        )
        runCurrent()
        r.write { it.copy(deniedPkjsWithoutPrimaryLayer = false) }
        runCurrent()
        assertEquals(emptyList(), r.requests, "a flip under the primary layer requested a restart")
        watcher.cancel()
    }
}
