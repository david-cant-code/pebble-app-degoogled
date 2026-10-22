package com.anopticlabs.gravel.notebook.alarms

import android.content.BroadcastReceiver
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/** Runs [block] on the I/O dispatcher inside the receiver's async window, finishing it whatever happens. */
internal fun BroadcastReceiver.runAsync(tag: String, block: suspend () -> Unit) {
    val result: BroadcastReceiver.PendingResult? = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Logger.withTag(tag).w(e) { "receiver work failed" }
        } finally {
            result?.finish()
        }
    }
}
