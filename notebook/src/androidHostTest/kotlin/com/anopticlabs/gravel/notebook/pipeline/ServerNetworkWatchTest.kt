package com.anopticlabs.gravel.notebook.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerNetworkWatchTest {

    private suspend fun settle() = repeat(5) { yield() }

    private val network = CaptureHold("waiting for Wi-Fi", waitsForNetwork = true)
    private val usable = MutableStateFlow(false)
    private val held = MutableStateFlow<CaptureHold?>(null)
    private val passes = MutableStateFlow(0)
    private var requests = 0
    private var scheduled = 0
    private var failSchedule = false

    private fun CoroutineScope.watch() = CoroutineScope(coroutineContext + SupervisorJob()).also { scope ->
        ServerNetworkWatch(usable, held, passes, { requests++ }, { scheduled++; if (failSchedule) error("no work manager") }, scope).start()
    }

    @Test
    fun usableWifiRunsTheQueueOnlyWhileThePassHeldForTheNetwork() = runBlocking<Unit> {
        val scope = watch()
        held.value = network
        usable.value = true
        settle()
        assertEquals(1, requests)
        usable.value = false
        settle()
        assertEquals(1, requests)
        held.value = CaptureHold("check your server")
        usable.value = true
        settle()
        assertEquals(1, requests)
        scope.cancel()
    }

    @Test
    fun theWifiStateAtStartIsNotAChange() = runBlocking<Unit> {
        usable.value = true
        held.value = network
        val scope = watch()
        settle()
        assertEquals(0, requests)
        scope.cancel()
    }

    @Test
    fun workIsScheduledAfterEachPassThatHeldForTheNetworkButNotForTheFirstValue() = runBlocking<Unit> {
        held.value = network
        val scope = watch()
        settle()
        assertEquals(0, scheduled)
        passes.value = 1
        settle()
        assertEquals(1, scheduled)
        held.value = CaptureHold("check your server")
        passes.value = 2
        settle()
        assertEquals(1, scheduled)
        held.value = null
        passes.value = 3
        settle()
        assertEquals(1, scheduled)
        scope.cancel()
    }

    @Test
    fun aFailedScheduleLeavesTheWatchRunning() = runBlocking<Unit> {
        failSchedule = true
        held.value = network
        val scope = watch()
        passes.value = 1
        settle()
        passes.value = 2
        settle()
        assertEquals(2, scheduled)
        scope.cancel()
    }
}
