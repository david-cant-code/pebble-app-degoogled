package coredevices.util.models

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.models.ModelCatalog
import com.anopticlabs.gravel.models.ModelRole
import com.anopticlabs.gravel.models.ModelStorageException
import com.anopticlabs.gravel.models.SpeechModelInstalls
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists a per-model heartbeat so a download job that is abandoned (process
 * death, hung blocking I/O) can be detected as stale across process restarts.
 */
internal class DownloadHeartbeatStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_download_heartbeat", Context.MODE_PRIVATE)

    fun record(slug: String) {
        prefs.edit().putLong(key(slug), System.currentTimeMillis()).apply()
    }

    fun clear(slug: String) {
        prefs.edit().remove(key(slug)).apply()
    }

    fun lastHeartbeat(slug: String): Long? =
        prefs.getLong(key(slug), -1L).takeIf { it > 0L }

    private fun key(slug: String) = "hb_$slug"
}

internal data class PendingDownload(val jobId: Int, val slug: String?, val stale: Boolean)

/**
 * One download per role at a time, lanes independent of each other; a slug
 * no catalog knows goes to the speech lane, where the provider refuses it.
 */
internal fun laneOf(slug: String?): ModelRole = slug?.let { ModelCatalog.byId(it)?.role } ?: ModelRole.Speech

internal fun jobsInLane(role: ModelRole, pending: List<PendingDownload>): List<PendingDownload> =
    pending.filter { laneOf(it.slug) == role }

/** Null when a live job for the same model is already alone in its lane. */
internal fun jobsReplacedBy(slug: String, pending: List<PendingDownload>): List<PendingDownload>? {
    val lane = jobsInLane(laneOf(slug), pending)
    if (lane.size == 1 && lane[0].slug == slug && !lane[0].stale) return null
    return lane
}

internal fun laneStatusAtStartup(role: ModelRole, pending: List<PendingDownload>): ModelDownloadStatus =
    pending.firstOrNull { it.slug != null && !it.stale && laneOf(it.slug) == role }
        ?.let { ModelDownloadStatus.Downloading(it.slug!!) }
        ?: ModelDownloadStatus.Idle

actual class ModelDownloadManager(
    private val context: Context
) {
    private val serviceComponentName = ComponentName(context, ModelDownloadService::class.java)
    private val jobScheduler: JobScheduler
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    internal val heartbeatStore = DownloadHeartbeatStore(context)

    private val laneStatuses: Map<ModelRole, MutableStateFlow<ModelDownloadStatus>> =
        pendingDownloads().let { pending ->
            ModelRole.entries.associateWith { MutableStateFlow(laneStatusAtStartup(it, pending)) }
        }

    /** The speech lane, the only one the dictation UI follows. */
    actual val downloadStatus: StateFlow<ModelDownloadStatus> =
        laneStatuses.getValue(ModelRole.Speech).asStateFlow()

    fun downloadStatus(role: ModelRole): StateFlow<ModelDownloadStatus> =
        laneStatuses.getValue(role).asStateFlow()

    init {
        // Evict any abandoned jobs on startup so the guard can't be blocked by them.
        reconcileStaleJobs()
    }

    private fun pendingServiceJobs() =
        jobScheduler.allPendingJobs.filter { it.service == serviceComponentName }

    private fun pendingDownloads(): List<PendingDownload> = pendingServiceJobs().map { job ->
        val slug = job.extras.getString(ModelDownloadService.KEY_MODEL_SLUG)
        PendingDownload(job.id, slug, slug == null || isJobStale(slug))
    }

    private fun isJobStale(slug: String): Boolean =
        DownloadJobLiveness.isStale(heartbeatStore.lastHeartbeat(slug), System.currentTimeMillis())

    private fun reconcileStaleJobs() {
        pendingServiceJobs().forEach { job ->
            val slug = job.extras.getString(ModelDownloadService.KEY_MODEL_SLUG)
            if (slug == null || isJobStale(slug)) {
                Logger.withTag("ModelDownloadManager").w {
                    "Cancelling stale/abandoned download job for $slug (id=${job.id})."
                }
                jobScheduler.cancel(job.id)
                slug?.let { heartbeatStore.clear(it) }
            }
        }
    }

    private fun buildNetworkRequest(allowMetered: Boolean): NetworkRequest {
        val builder = NetworkRequest.Builder()
            .addCapability(NET_CAPABILITY_INTERNET)
            .removeCapability(NET_CAPABILITY_NOT_VPN)
        if (!allowMetered) {
            builder.addCapability(NET_CAPABILITY_NOT_METERED)
        }
        return builder.build()
    }

    fun updateDownloadStatus(slug: String, status: ModelDownloadStatus) {
        laneStatuses.getValue(laneOf(slug)).value = status
    }

    private fun slugToJobId(modelSlug: String): Int {
        return "modelJob-$modelSlug".hashCode()
    }

    @RequiresPermission(Manifest.permission.RUN_USER_INITIATED_JOBS)
    private fun buildJobInfo(modelSlug: String, modelSizeMb: Int, networkRequest: NetworkRequest, allowMetered: Boolean): JobInfo {
        val builder = JobInfo.Builder(slugToJobId(modelSlug), serviceComponentName)
            .setExtras(
                android.os.PersistableBundle().apply {
                    putString(ModelDownloadService.KEY_MODEL_SLUG, modelSlug)
                }
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder
                .setRequiredNetwork(networkRequest)
                .setEstimatedNetworkBytes(modelSizeMb * 1024L * 1024L, 1 * 1024L * 1024L)
        } else {
            builder.setRequiredNetworkType(
                if (!allowMetered) {
                    JobInfo.NETWORK_TYPE_UNMETERED
                } else {
                    JobInfo.NETWORK_TYPE_ANY
                }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setUserInitiated(true)
        }
        return builder.build()
    }

    /** A hung job in the lane can never permanently block a new download there. */
    private fun scheduleDownload(modelInfo: ModelInfo, allowMetered: Boolean): Boolean {
        val replaced = jobsReplacedBy(modelInfo.slug, pendingDownloads())
        if (replaced == null) {
            Logger.withTag("ModelDownloadManager").i {
                "Download already in progress for ${modelInfo.slug}, skipping."
            }
            return true
        }
        replaced.forEach { job ->
            Logger.withTag("ModelDownloadManager").w {
                "Cancelling existing download job for ${job.slug} to download ${modelInfo.slug}."
            }
            jobScheduler.cancel(job.jobId)
            job.slug?.let { heartbeatStore.clear(it) }
        }
        heartbeatStore.clear(modelInfo.slug)
        val info = buildJobInfo(
            modelSlug = modelInfo.slug,
            modelSizeMb = modelInfo.sizeInMB,
            networkRequest = buildNetworkRequest(allowMetered),
            allowMetered = allowMetered
        )
        return jobScheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    actual fun downloadSTTModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean =
        scheduleDownload(modelInfo, allowMetered = allowMetered)

    /** Cancels the speech lane; a text download keeps running. */
    actual fun cancelDownload() = cancelDownload(ModelRole.Speech)

    fun cancelDownload(role: ModelRole) {
        jobsInLane(role, pendingDownloads()).forEach { job ->
            jobScheduler.cancel(job.jobId)
            job.slug?.let { heartbeatStore.clear(it) }
        }
    }
}

/**
 * The downloads a [ModelDownloadService] is running, keyed by job id: the
 * platform delivers every job's start and stop to the one service
 * instance (android16-release, `JobService` class documentation), so a
 * stop must reach only the job it names.
 */
internal class RunningDownloads {
    class Entry(val slug: String, val job: Job, val stoppedBySystem: AtomicBoolean = AtomicBoolean(false))

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun add(jobId: Int, entry: Entry) {
        entries[jobId] = entry
    }

    fun stop(jobId: Int, cause: CancellationException): Entry? =
        entries[jobId]?.also {
            it.stoppedBySystem.set(true)
            it.job.cancel(cause)
        }

    fun remove(jobId: Int, entry: Entry) {
        entries.remove(jobId, entry)
    }
}

class ModelDownloadService : JobService(), KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelDownloadManager: ModelDownloadManager by inject()
    private val speechInstalls: SpeechModelInstalls by inject()
    private val heartbeatStore: DownloadHeartbeatStore get() = modelDownloadManager.heartbeatStore
    private val running = RunningDownloads()

    companion object {
        const val KEY_MODEL_SLUG = "model_slug"
        private const val CHANNEL_ID = "model_download_channel"
        private val logger = Logger.withTag("ModelDownloadService")
    }

    private fun notifBuilder() = NotificationCompat.Builder(
        applicationContext,
        CHANNEL_ID
    ).setLocalOnly(true)

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobParams = params ?: return false
        val modelSlug = jobParams.extras.getString(KEY_MODEL_SLUG) ?: return false

        logger.i { "Starting download job for model: $modelSlug" }
        createChannel()
        val notification = notifBuilder()
            .setContentTitle("Downloading Model")
            .setContentText("Downloading model: $modelSlug.\nThis could take a few minutes...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setGroup("model_downloads")
            .build()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(modelSlug.hashCode(), notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(jobParams, modelSlug.hashCode(), notification, JOB_END_NOTIFICATION_POLICY_DETACH)
        }
        val stoppedBySystem = AtomicBoolean(false)
        lateinit var entry: RunningDownloads.Entry
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val heartbeat = launch {
                while (isActive) {
                    heartbeatStore.record(modelSlug)
                    delay(DownloadJobLiveness.DEFAULT_HEARTBEAT_INTERVAL_MILLIS)
                }
            }
            try {
                heartbeatStore.record(modelSlug)
                modelDownloadManager.updateDownloadStatus(modelSlug, ModelDownloadStatus.Downloading(modelSlug))
                withTimeout(DownloadJobLiveness.timeoutFor(ModelCatalog.byId(modelSlug)?.sizeBytes ?: 0L)) {
                    downloadModel(modelSlug)
                }
                logger.i { "Completed download job for model: $modelSlug" }
                notificationManager.notify(
                    modelSlug.hashCode(),
                    notifBuilder()
                        .setContentTitle("Model Downloaded")
                        .setContentText("Successfully downloaded model: $modelSlug")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setOngoing(false)
                        .build()
                )
                modelDownloadManager.updateDownloadStatus(modelSlug, ModelDownloadStatus.Idle)
                speechInstalls.downloaded(modelSlug)
            } catch (e: TimeoutCancellationException) {
                logger.e(e) { "Timed out downloading model: $modelSlug" }
                notificationManager.notify(
                    modelSlug.hashCode(),
                    notifBuilder()
                        .setContentTitle("Model Download Failed")
                        .setContentText("Download timed out: $modelSlug")
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setOngoing(false)
                        .build()
                )
                modelDownloadManager.updateDownloadStatus(
                    modelSlug,
                    ModelDownloadStatus.Failed(modelSlug, "Download timed out"),
                )
            } catch (e: CancellationException) {
                // Stopped by the system via onStopJob; it owns the reschedule decision.
                throw e
            } catch (e: Throwable) {
                logger.e(e) { "Failed download job for model: $modelSlug" }
                val noSpace = e is ModelStorageException && e.outOfSpace
                notificationManager.notify(
                    modelSlug.hashCode(),
                    notifBuilder()
                        .setContentTitle("Model Download Failed")
                        .setContentText(
                            if (noSpace) "Not enough free storage for model: $modelSlug"
                            else "Failed to download model: $modelSlug"
                        )
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setOngoing(false)
                        .build()
                )
                modelDownloadManager.updateDownloadStatus(
                    modelSlug,
                    ModelDownloadStatus.Failed(modelSlug, if (noSpace) "Not enough free storage" else "Download failed"),
                )
            } finally {
                heartbeat.cancel()
                heartbeatStore.clear(modelSlug)
                running.remove(jobParams.jobId, entry)
                // If the system stopped us, it reschedules based on onStopJob's
                // return value; calling jobFinished here would be a no-op anyway.
                if (!stoppedBySystem.get()) {
                    jobFinished(jobParams, false)
                }
            }
        }
        entry = RunningDownloads.Entry(modelSlug, job, stoppedBySystem)
        running.add(jobParams.jobId, entry)
        job.start()
        return true
    }

    // The slug names the catalog entry to install; the provider verifies the
    // download against its pin before anything becomes loadable.
    private suspend fun downloadModel(modelSlug: String) {
        val modelProvider: CactusModelPathProvider by inject()
        modelProvider.getModelPath(modelSlug)
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params?.stopReason
        } else {
            null
        }
        val entry = params?.let { running.stop(it.jobId, CancellationException("Job stopped by system, reason=$reason")) }
        val modelSlug = entry?.slug ?: params?.extras?.getString(KEY_MODEL_SLUG)
        logger.i { "Job stopped for model: $modelSlug, reason = $reason" }
        modelSlug?.let { heartbeatStore.clear(it) }
        val title = when (reason) {
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Model Download Paused"
            JobParameters.STOP_REASON_TIMEOUT -> "Model Download Error"
            else -> "Model Download Cancelled"
        }
        val text = when (reason) {
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Download paused due to network conditions."
            JobParameters.STOP_REASON_TIMEOUT -> "Timed out trying to download model: $modelSlug."
            else -> "Cancelled download."
        }
        val icon = when (reason) {
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> android.R.drawable.stat_sys_warning
            JobParameters.STOP_REASON_TIMEOUT -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.stat_sys_download_done
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val cancelledNotification = notifBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(false)
            .build()

        if (modelSlug != null) {
            notificationManager.notify(modelSlug.hashCode(), cancelledNotification)
            modelDownloadManager.updateDownloadStatus(modelSlug, ModelDownloadStatus.Idle)
        }
        // Let the system retry transient stops; don't retry app/user-driven ones.
        return shouldReschedule(reason)
    }

    private fun shouldReschedule(stopReason: Int?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || stopReason == null) return true
        return when (stopReason) {
            JobParameters.STOP_REASON_CANCELLED_BY_APP,
            JobParameters.STOP_REASON_USER -> false
            else -> true
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Speech and language model download progress"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

}