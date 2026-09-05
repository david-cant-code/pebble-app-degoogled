package coredevices.coreapp.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import co.touchlab.kermit.Logger
import coredevices.util.CoreConfigHolder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork, debug builds only: sets the dictation debug hooks from adb, so an
 * emulator session can be driven without walking the settings UI. Lives
 * in the debug source set, so release builds carry neither the class nor
 * the manifest entry. Each extra is optional; omitted ones are unchanged.
 *
 *     adb shell am broadcast -a coredevices.coreapp.SET_STT_DEBUG \
 *       -n com.anopticlabs.gravel/coredevices.coreapp.debug.SttDebugReceiver \
 *       --ez substituteAudio true --ez slowDecode true \
 *       --ez singleThread false --ez captureDump false
 *
 * With `--ez postTestNotification true` it also posts a notification with
 * a reply action from the app itself, titled "Test Notification", which
 * the notification handler forwards to the connected watch even while the
 * phone's screen is on; the watch's reply action opens the dictation UI.
 * Posting from inside the app process keeps the watch session alive,
 * which an instrumentation run would not (it restarts the process). The
 * app needs POST_NOTIFICATIONS granted for the notification to show.
 *
 * Guarded by android.permission.DUMP in the manifest like upstream's adb
 * receivers (DESIGN_NOTES describes the gate once). The hooks themselves
 * re-check the build type before acting (see STTConfig).
 */
class SttDebugReceiver : BroadcastReceiver(), KoinComponent {
    private val coreConfigHolder: CoreConfigHolder by inject()
    private val logger = Logger.withTag("SttDebugReceiver")

    private fun postTestNotification(context: Context) {
        val channelId = "dictation-debug"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Dictation debug", NotificationManager.IMPORTANCE_HIGH),
        )
        val replyIntent = PendingIntent.getBroadcast(
            context, 0, Intent("coredevices.coreapp.debug.DICTATION_REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        // A reply action must not be marked as showing a UI, or the
        // notification handler drops it as a phone-only action.
        val action = NotificationCompat.Action.Builder(0, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder("reply").setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val posted = java.time.LocalTime.now().withNano(0)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Test Notification")
            // The time keeps repeated posts distinct, so each one reaches the
            // watch as a new notification rather than a duplicate.
            .setContentText("Reply with your voice, posted at $posted")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(action)
            .build()
        manager.notify((System.currentTimeMillis() % 100_000).toInt(), notification)
        logger.i { "posted the dictation test notification" }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("postTestNotification", false)) {
            postTestNotification(context)
        }
        val current = coreConfigHolder.config.value
        val stt = current.sttConfig
        val extras = intent.extras
        fun flag(name: String, old: Boolean): Boolean =
            if (extras?.containsKey(name) == true) intent.getBooleanExtra(name, old) else old
        val updated = stt.copy(
            debugSubstituteAudio = flag("substituteAudio", stt.debugSubstituteAudio),
            debugSlowDecode = flag("slowDecode", stt.debugSlowDecode),
            debugSingleThread = flag("singleThread", stt.debugSingleThread),
            debugCaptureDump = flag("captureDump", stt.debugCaptureDump),
        )
        coreConfigHolder.update(current.copy(sttConfig = updated))
        val summary = "substituteAudio=${updated.debugSubstituteAudio} slowDecode=${updated.debugSlowDecode} " +
            "singleThread=${updated.debugSingleThread} captureDump=${updated.debugCaptureDump}"
        logger.i { "STT debug hooks: $summary" }
        resultCode = 0
        resultData = summary
    }
}
