package com.anopticlabs.gravel.notebook.alarms

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Whether any reminder is stored, re-read on each refresh tick; the app requires the exact-alarm
 * grant while one is. The first value is false before any read, and a failed read counts as none,
 * so the app's permission flow never waits on or dies with the Notebook's storage.
 */
class ReminderPresence(private val reminders: ReminderStore, refresh: ReminderRefreshSignal) {

    val hasReminders: Flow<Boolean> = refresh.events
        .onStart { emit(Unit) }
        .map { read() }
        .onStart { emit(false) }
        .flowOn(Dispatchers.IO)

    private fun read(): Boolean = try {
        reminders.readAll().isNotEmpty()
    } catch (e: Exception) {
        logger.w(e) { "reminders unreadable; no exact-alarm grant required" }
        false
    }

    private companion object {
        val logger = Logger.withTag("ReminderPresence")
    }
}
