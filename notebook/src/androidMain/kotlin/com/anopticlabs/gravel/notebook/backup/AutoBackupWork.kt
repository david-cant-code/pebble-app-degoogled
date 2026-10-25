package com.anopticlabs.gravel.notebook.backup

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anopticlabs.gravel.notebook.workManagerOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

/** When automatic runs happen. */
interface AutoBackupScheduler {
    /**
     * A run about an hour from now unless one is already waiting. A run under way would swallow
     * the request, so while one runs for the change slot ([running]) the request takes the other.
     */
    fun afterChange(running: String?)

    /** The run every [PERIODIC_HOURS] hours that backs up what the change runs missed and reads the newest backup back. */
    fun schedulePeriodic()

    /** A run as soon as the work can start, unless one asked for this way is waiting or running. */
    fun runSoon()

    fun cancel()

    companion object {
        const val CHANGE_DELAY_MINUTES = 60L
        const val PERIODIC_HOURS = 12L
        const val BACKOFF_MINUTES = 30L
    }
}

/** [AutoBackupScheduler] on WorkManager: one unique work per slot, each run tagged with its slot. */
class WorkAutoBackupScheduler internal constructor(private val workManager: () -> WorkManager) : AutoBackupScheduler {
    constructor(context: Context) : this({ WorkManager.getInstance(context) })

    override fun afterChange(running: String?) {
        val work = workManagerOrNull(workManager) ?: return
        val slot = changeSlotFor(running)
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(AutoBackupScheduler.CHANGE_DELAY_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AutoBackupScheduler.BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(SLOT_TAG + slot)
            .build()
        work.enqueueUniqueWork(WORK_PREFIX + slot, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val work = workManagerOrNull(workManager) ?: return
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(AutoBackupScheduler.PERIODIC_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AutoBackupScheduler.BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(SLOT_TAG + AutoBackupRunner.SLOT_PERIODIC)
            .build()
        work.enqueueUniquePeriodicWork(WORK_PREFIX + AutoBackupRunner.SLOT_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun runSoon() {
        val work = workManagerOrNull(workManager) ?: return
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AutoBackupScheduler.BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(SLOT_TAG + AutoBackupRunner.SLOT_CHECK)
            .build()
        work.enqueueUniqueWork(WORK_PREFIX + AutoBackupRunner.SLOT_CHECK, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        val work = workManagerOrNull(workManager) ?: return
        for (slot in listOf(AutoBackupRunner.SLOT_CHANGE, AutoBackupRunner.SLOT_NEXT, AutoBackupRunner.SLOT_PERIODIC, AutoBackupRunner.SLOT_CHECK)) {
            work.cancelUniqueWork(WORK_PREFIX + slot)
        }
    }

    companion object {
        const val WORK_PREFIX = "notebook_auto_backup_"
        const val SLOT_TAG = "notebook_auto_backup_slot:"
    }
}

/** One automatic run for the slot its tag names, then the checks, failed runs included. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        val result = try {
            get<AutoBackupRunner<Uri>>().run(slotOf(tags), attempt = runAttemptCount)
        } finally {
            get<AutoBackupChecks>().check()
        }
        return when (outcomeOf(result)) {
            WorkOutcome.SUCCESS -> Result.success()
            WorkOutcome.RETRY -> Result.retry()
            WorkOutcome.FAILURE -> Result.failure()
        }
    }
}

/** The change slot to ask while [running] runs: a request for the slot under way would be dropped. */
internal fun changeSlotFor(running: String?): String =
    if (running == AutoBackupRunner.SLOT_CHANGE) AutoBackupRunner.SLOT_NEXT else AutoBackupRunner.SLOT_CHANGE

internal fun slotOf(tags: Set<String>): String =
    tags.firstOrNull { it.startsWith(WorkAutoBackupScheduler.SLOT_TAG) }?.removePrefix(WorkAutoBackupScheduler.SLOT_TAG) ?: AutoBackupRunner.SLOT_PERIODIC

internal enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

/** A failure the user has been told of ends the work until the next change or period. */
internal fun outcomeOf(result: AutoRunResult): WorkOutcome = when (result) {
    AutoRunResult.Off, AutoRunResult.Confirmed, is AutoRunResult.Saved -> WorkOutcome.SUCCESS
    AutoRunResult.Busy -> WorkOutcome.RETRY
    is AutoRunResult.Failed -> if (result.retry) WorkOutcome.RETRY else WorkOutcome.FAILURE
}
