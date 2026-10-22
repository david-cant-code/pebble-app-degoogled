package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Asks for a run ([request]) when one of [watched] changes, so captures held or waiting under the old state meet the
 * new one. Each of [watched] gives its current value to a new collector, as a StateFlow does.
 */
class SpeechSettingWatch(
    private val watched: List<Flow<Any?>>,
    private val request: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.w(e) { "speech setting watch failed" } }),
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        for (flow in watched) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                flow.distinctUntilChanged().drop(1).collect { request() }
            }
        }
    }

    private companion object {
        val logger = Logger.withTag("SpeechSettingWatch")
    }
}
