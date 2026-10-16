package com.anopticlabs.gravel.notebook.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechSettingWatchTest {

    private suspend fun settle() = repeat(5) { yield() }

    /** The app's settings as one value: the speech setting first, a setting the watch does not follow second. */
    private val settings = MutableStateFlow("local" to 0)
    private var requests = 0

    private fun CoroutineScope.watch(starts: Int = 1, vararg others: Flow<Any?>) = CoroutineScope(coroutineContext + SupervisorJob()).also { scope ->
        val watch = SpeechSettingWatch(listOf(settings.map { it.first }, *others), { requests++ }, scope)
        repeat(starts) { watch.start() }
    }

    @Test
    fun aChangeOfTheSpeechSettingAsksForARun() = runBlocking<Unit> {
        val scope = watch()
        settings.value = "server" to 0
        settle()
        assertEquals(1, requests)
        settings.value = "local" to 0
        settle()
        assertEquals(2, requests)
        scope.cancel()
    }

    @Test
    fun aChangeOfAnyWatchedValueAsksForARun() = runBlocking<Unit> {
        val installs = MutableStateFlow(3)
        val scope = watch(others = arrayOf(installs))
        settle()
        assertEquals(0, requests)
        installs.value = 4
        settle()
        assertEquals(1, requests)
        settings.value = "server" to 0
        settle()
        assertEquals(2, requests)
        scope.cancel()
    }

    @Test
    fun theSettingAtStartIsNotAChange() = runBlocking<Unit> {
        settings.value = "server" to 0
        val scope = watch()
        settle()
        assertEquals(0, requests)
        scope.cancel()
    }

    @Test
    fun aChangeOfAnotherSettingAsksForNothing() = runBlocking<Unit> {
        val scope = watch()
        settings.value = "local" to 1
        settle()
        assertEquals(0, requests)
        scope.cancel()
    }

    @Test
    fun startingTwiceWatchesOnce() = runBlocking<Unit> {
        val scope = watch(starts = 2)
        settings.value = "server" to 0
        settle()
        assertEquals(1, requests)
        scope.cancel()
    }
}
