package com.anopticlabs.gravel.notebook.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.SERVER_TRY_SPACING_MILLIS
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

/** The work that runs a drain once a network is connected, for captures that wait on the network while the process is not running. */
fun interface NetworkWork {
    /** Enqueues the work unless it is already waiting or running. */
    fun schedule()
}

class WorkManagerNetworkWork internal constructor(private val workManager: () -> WorkManager) : NetworkWork {
    constructor(context: Context) : this({ WorkManager.getInstance(context) })

    override fun schedule() {
        val work = workManagerOrNull(workManager) ?: return
        // Any connected network: the drain applies the Wi-Fi rule itself, since JobScheduler judges a network
        // constraint by the app's default network alone (frameworks/base android16-release,
        // ConnectivityController.maybeRegisterDefaultNetworkCallbackLocked), a VPN included (see AndroidUnmeteredWifi).
        val request = OneTimeWorkRequestBuilder<NetworkWaitWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "notebook_captures_on_network"
        const val RETRY_MINUTES = 15L
    }
}

/** The work that runs a drain once a long capture's next server try is due, on any connected network; each recorded try replaces it. */
class WorkManagerServerTryWork internal constructor(private val workManager: () -> WorkManager) : NetworkWork {
    constructor(context: Context) : this({ WorkManager.getInstance(context) })

    override fun schedule() {
        val work = workManagerOrNull(workManager) ?: return
        val request = OneTimeWorkRequestBuilder<NetworkWaitWorker>()
            .setInitialDelay(SERVER_TRY_SPACING_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, SERVER_TRY_SPACING_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "notebook_server_try"
    }
}

/** Starts a coordinator run and asks to run again while the drain still holds for the network. */
class NetworkWaitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        val coordinator = get<CaptureCoordinator>()
        coordinator.requestAgain()
        coordinator.working.first { !it }
        return if (get<CaptureDrain>().held.value?.waitsForNetwork == true) Result.retry() else Result.success()
    }
}

/**
 * Asks for a run ([request], which makes a run under way go round) when the server's Wi-Fi rule starts allowing a
 * request ([usable] turns true) while the drain holds for the network, and schedules [work] after each pass that
 * ends so, for when the process is not running.
 */
class ServerNetworkWatch(
    private val usable: StateFlow<Boolean>,
    private val held: StateFlow<CaptureHold?>,
    private val passes: Flow<Int>,
    private val request: () -> Unit,
    private val work: NetworkWork,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.w(e) { "network watch failed" } }),
) {
    private val started = AtomicBoolean(false)

    /** Call before the first coordinator run, so no pass ends before the watch is collecting. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            usable.drop(1).collect { if (it && held.value?.waitsForNetwork == true) request() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            passes.drop(1).collect {
                if (held.value?.waitsForNetwork == true) {
                    try {
                        work.schedule()
                    } catch (e: Exception) {
                        logger.w(e) { "network work not scheduled" }
                    }
                }
            }
        }
    }

    private companion object {
        val logger = Logger.withTag("ServerNetworkWatch")
    }
}
