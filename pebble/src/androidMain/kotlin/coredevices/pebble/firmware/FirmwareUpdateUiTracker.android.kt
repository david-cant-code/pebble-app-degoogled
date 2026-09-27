package coredevices.pebble.firmware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.anopticlabs.gravel.firmware.EXTRA_FIRMWARE_PICKER_TOKEN
import com.anopticlabs.gravel.firmware.EXTRA_FIRMWARE_PICKER_WATCH
import com.anopticlabs.gravel.firmware.FirmwareNotificationToken
import com.eygraber.uri.toAndroidUri
import coredevices.pebble.RealPebbleDeepLinkHandler.Companion.NOTIFICATION_INTENT_URI_SHOW_WATCHES
import coredevices.util.R
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleIdentifier

actual fun notifyFirmwareUpdate(
    appContext: AppContext,
    title: String,
    body: String,
    key: Int,
    identifier: PebbleIdentifier,
) {
    val context = appContext.context
    context.createFwupNotificationChannel()

    val viewIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    viewIntent?.setData(NOTIFICATION_INTENT_URI_SHOW_WATCHES.toAndroidUri())
    // Gravel: MainActivity decides whether these open the build picker
    // (firmwarePickerRouteForNotification).
    viewIntent?.putExtra(EXTRA_FIRMWARE_PICKER_WATCH, identifier.asString)
    viewIntent?.putExtra(EXTRA_FIRMWARE_PICKER_TOKEN, FirmwareNotificationToken.value)
    // Extras do not tell PendingIntents apart, so the request code is the
    // notification's per-watch key, and FLAG_UPDATE_CURRENT replaces the extras
    // of one posted by an earlier process (android16-release, PendingIntent
    // class documentation and FLAG_UPDATE_CURRENT).
    val viewPendingIntent = PendingIntent.getActivity(
        context,
        key,
        viewIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(
        context,
        CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(body)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(viewPendingIntent)
        .setAutoCancel(true)
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(key, builder.build())
}

private const val CHANNEL_ID = "firmware_update_channel"

private fun Context.createFwupNotificationChannel() {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Firmware Updates",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Firmware udpate notifications"
    }
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}

actual fun removeFirmwareUpdateNotification(appContext: AppContext, key: Int) {
    val notificationManager =
        appContext.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(key)
}
