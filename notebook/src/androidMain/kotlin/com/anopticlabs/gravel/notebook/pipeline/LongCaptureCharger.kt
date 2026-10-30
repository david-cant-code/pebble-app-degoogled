package com.anopticlabs.gravel.notebook.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.workManagerOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** True while the phone is on any power source; when the system gives no battery state, it counts as on power. */
fun isPluggedIn(context: Context): Boolean {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
    return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}

/** The work that starts processing once the phone charges, for when the process is not running then. */
fun interface ChargingWork {
    /** Enqueues the work unless it is already waiting or running. */
    fun schedule()
}

class WorkManagerChargingWork internal constructor(private val workManager: () -> WorkManager) : ChargingWork {
    constructor(context: Context) : this({ WorkManager.getInstance(context) })

    override fun schedule() {
        val work = workManagerOrNull(workManager) ?: return
        // JobScheduler's charging, not plugged in: android15-release and android16-release
        // JobSchedulerService.BatteryStateTracker.isConsideredCharging also count a held charge of 70% or more under an
        // adaptive charging policy; in android14-release BatteryStateTracker.isCharging is ACTION_CHARGING alone.
        val request = OneTimeWorkRequestBuilder<LongCaptureChargingWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "notebook_long_captures_on_charger"
        const val RETRY_MINUTES = 15L
    }
}

/** Whether the charging work asks to run again after a run: long captures still wait for power and the run did not stop early. */
internal fun chargingWorkRetries(held: CaptureHold?, waitingForPower: Int): Boolean =
    (held == null || held.waitOnly) && waitingForPower > 0

/**
 * Starts a coordinator run and holds the job until the run ends, then asks to run again per
 * [chargingWorkRetries]; a stop by the system cancels the wait.
 */
class LongCaptureChargingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        val coordinator = get<CaptureCoordinator>()
        // Working is true when this returns, whether it started a run or made the one under way go round.
        coordinator.requestAgain()
        coordinator.working.first { !it }
        val drain = get<CaptureDrain>()
        return if (chargingWorkRetries(drain.held.value, drain.waitingForPower.value)) Result.retry() else Result.success()
    }
}

/** Schedules [work] after a drain pass ([passes] after its first value) when long captures are still [waiting] for power. */
internal suspend fun scheduleWhileWaiting(passes: Flow<Int>, waiting: () -> Int, work: ChargingWork) {
    passes.drop(1).collect {
        if (waiting() > 0) {
            try {
                work.schedule()
            } catch (e: Exception) {
                logger.w(e) { "charging work not scheduled" }
            }
        }
    }
}

/**
 * Starts the long captures the charger rule holds once the phone is on power: at once while the
 * process runs, and through [work] while captures wait, for when it does not.
 */
class LongCaptureCharger(
    private val context: Context,
    private val passes: StateFlow<Int>,
    private val waitingForPower: StateFlow<Int>,
    private val controls: () -> ProcessingControls,
    private val work: ChargingWork,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.w(e) { "charger watch failed" } })

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = controls().onPowerChanged()
    }

    /** Call before the first coordinator run, so no pass ends before the watch is collecting. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // android16-release BroadcastSkipPolicy.shouldSkipMessage lets the system's broadcasts reach a receiver that is not exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scheduleWhileWaiting(passes, { waitingForPower.value }, work) }
    }
}

private val logger = Logger.withTag("LongCaptureCharger")
