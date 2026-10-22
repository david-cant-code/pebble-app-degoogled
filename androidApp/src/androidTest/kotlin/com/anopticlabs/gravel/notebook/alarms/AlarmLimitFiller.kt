package com.anopticlabs.gravel.notebook.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Throwaway alarms in this app's uid up to Android's per-app alarm limit; [close] cancels them. */
class AlarmLimitFiller(private val context: Context) : AutoCloseable {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val fillers = mutableListOf<PendingIntent>()

    /** Sets one more filler a day ahead; false when Android refuses it at the limit. */
    fun addOne(): Boolean {
        val pending = PendingIntent.getBroadcast(
            context, FILLER_BASE + fillers.size, Intent(FILLER_ACTION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        fillers += pending
        return try {
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 86_400_000L, pending)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun fillToLimit() {
        while (addOne()) check(fillers.size < 2_000) { "no per-app alarm limit met" }
    }

    override fun close() {
        fillers.forEach { alarmManager.cancel(it); it.cancel() }
    }

    private companion object {
        const val FILLER_BASE = 91_000_000
        const val FILLER_ACTION = "com.anopticlabs.gravel.test.ALARM_LIMIT_FILLER"
    }
}
