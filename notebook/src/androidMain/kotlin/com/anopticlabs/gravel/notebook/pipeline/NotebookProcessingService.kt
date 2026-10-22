package com.anopticlabs.gravel.notebook.pipeline

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Starts [NotebookProcessingService] and holds a partial wake lock while captures are processed
 * or a recording is under way, since a foreground service does not keep the device awake with
 * the screen off (developer.android.com, "Choose the right API to keep the device awake"). The
 * lock is not reference counted, so each acquire replaces the pending timeout (android16-release,
 * `PowerManager.WakeLock.acquireLocked`); a stage that makes no progress for 30 minutes runs on
 * without it.
 */
class AndroidCaptureKeepAlive(private val context: Context) : CaptureKeepAlive, RecordingKeepAlive {

    private val wakeLock by lazy {
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    private val lock = Any()
    private var processing = false
    private var recording = false

    private val _held = MutableStateFlow(false)

    /** True while either hold is taken; the service stops itself when this turns false. */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    private val _recordingHeld = MutableStateFlow(false)

    /** True while a recording holds the service; its notification says so. */
    val recordingHeld: StateFlow<Boolean> = _recordingHeld.asStateFlow()

    override fun hold() {
        synchronized(lock) {
            processing = true
            settle()
        }
        startService()
    }

    override fun extend() = synchronized(lock) {
        if (_held.value) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    override fun release() = synchronized(lock) {
        processing = false
        settle()
    }

    override fun holdRecording() {
        synchronized(lock) {
            recording = true
            settle()
        }
        startService()
    }

    override fun releaseRecording() = synchronized(lock) {
        recording = false
        settle()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, NotebookProcessingService::class.java))
        } catch (e: Exception) {
            // Expected from the background without an exemption; see InferenceBoostService.
            logger.w { "processing service not started: ${e::class.simpleName}" }
        }
    }

    // Under the lock, so holds and releases from different threads reach the wake lock in order.
    private fun settle() {
        val held = processing || recording
        _recordingHeld.value = recording
        _held.value = held
        if (held) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) else wakeLock.release()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "gravel:notebook-processing"
        const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        val logger = Logger.withTag("CaptureKeepAlive")
    }
}

/**
 * Keeps the app process in the foreground while Notebook captures are processed with no activity
 * resumed, and from a recording's start until it is queued; the work itself runs in
 * [CaptureCoordinator] and the recorder. It stops itself once
 * [AndroidCaptureKeepAlive.held] turns false: a service started with startForegroundService and
 * brought down before it called startForeground crashes the app (android16-release,
 * `ActiveServices.bringDownServiceLocked`).
 */
class NotebookProcessingService : Service(), KoinComponent {

    private val keepAlive: AndroidCaptureKeepAlive by inject()
    private val scope = MainScope()
    private var watch: Job? = null
    private var recordingShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordingShown = keepAlive.recordingHeld.value
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(recordingShown), type)
        } catch (e: Exception) {
            logger.w { "processing service could not enter the foreground: ${e::class.simpleName}" }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        watch?.cancel()
        // An id older than the last start does not stop the service (android16-release,
        // ActiveServices.stopServiceTokenLocked), so a hold taken again keeps it.
        watch = scope.launch {
            launch { keepAlive.recordingHeld.collect { if (it != recordingShown) update(it) } }
            keepAlive.held.first { !it }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // A startForeground call after the first re-checks the background start rules
    // (android16-release, ActiveServices.setServiceForegroundInnerLocked), so a title change is posted.
    private fun update(recording: Boolean) {
        recordingShown = recording
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(recording))
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(recording: Boolean): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(getString(R.string.notebook_processing_channel))
                .build(),
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(coredevices.util.R.drawable.ic_notification)
            .setContentTitle(getString(if (recording) R.string.notebook_recording_title else R.string.notebook_processing_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "notebook_processing"
        const val NOTIFICATION_ID = 0x4e42
        val logger = Logger.withTag("NotebookProcessingService")
    }
}
