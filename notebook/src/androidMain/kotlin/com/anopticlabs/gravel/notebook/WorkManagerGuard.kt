package com.anopticlabs.gravel.notebook

import androidx.work.WorkManager
import co.touchlab.kermit.Logger

private val logger = Logger.withTag("WorkManagerGuard")

/**
 * [get]'s WorkManager, or null when getting it throws: WorkManager initializes on first use, which
 * throws an Error on the devices `scheduleBackgroundJob` in MainApplication.kt guards against.
 */
internal fun workManagerOrNull(get: () -> WorkManager): WorkManager? = try {
    get()
} catch (e: Throwable) {
    logger.e(e) { "WorkManager unavailable" }
    null
}
