package coredevices.coreapp

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import co.touchlab.kermit.Logger
import coredevices.util.R

/**
 * Fork-owned shortService foreground service that holds process priority
 * during local Cactus transcription. Upstream's equivalent
 * (InferenceForegroundService) lives in the unplugged :experimental
 * module, so without this the app transcribes at background priority
 * whenever the watch-connection foreground service is toggled off, and
 * modern Android CPU restrictions can push inference past the dictation
 * timeouts. It gets its own class name on purpose: no upstream call site
 * references the experimental service, so the same-FQN stub pattern buys
 * nothing here. The upstream InferenceActivity trampoline is deliberately
 * not ported.
 *
 * Lifecycle is driven by [AndroidInferenceBoost]'s ref count. On API 31+
 * the start from the background is only permitted through the
 * companion-device exemption (merged from libpebble3's manifest); without
 * it (BT Classic watches, CDM association revoked) the start throws and
 * transcription simply runs unboosted.
 */
class InferenceBoostService : Service() {
    companion object {
        private val logger = Logger.withTag("InferenceBoostService")

        // Same ids as the upstream experimental service, so a future
        // upstream port of this feature collides visibly instead of
        // running two boost notifications side by side.
        private const val NOTIFICATION_ID = 7631
        private const val CHANNEL_ID = "inference_fg"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
            .setName("Speech Processing")
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)

        // With POST_NOTIFICATIONS denied the notification stays hidden but
        // the service still gains foreground priority, so no permission
        // handling is needed here.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Processing speech…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            logger.w(e) { "Could not enter the foreground; transcription continues unboosted" }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // Fork addition upstream lacks: the system delivers this when the
    // shortService time budget (about 3 minutes) expires, and a service
    // that does not stop promptly ANRs the app. A transcription that
    // outlives the budget just loses its boost.
    override fun onTimeout(startId: Int) {
        logger.w { "shortService budget expired; dropping the boost" }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
