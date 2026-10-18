package com.anopticlabs.gravel.notebook.reminders

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process tick after a write to the reminders, the event log or the parked claims,
 * so a live screen re-reads the stores. Ticks coalesce, and one emitted while nothing collects is
 * dropped: the next screen reads fresh anyway.
 */
class ReminderRefreshSignal {
    private val ticks = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Unit> = ticks.asSharedFlow()

    fun emit() {
        ticks.tryEmit(Unit)
    }
}
