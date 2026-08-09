package coredevices.coreapp.util

import PlatformContext
import android.Manifest
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the fork's notifyLocal/cancelNotifyLocal actuals, the replacement for
 * the deleted kmpnotifier local-notification path. Its only production caller
 * is the Cactus incompatible-model nudge, which is dead on a clean install
 * (no downloaded models) and only fires in the field on a weights-version
 * bump, with failures swallowed by the caller's catch, so nothing else would
 * surface a broken implementation before it mattered.
 */
class LocalNotifyTest {
    private companion object {
        // Arbitrary test id: since the caller-supplied id took over dedup
        // duties from the old file-private constant, the value itself is no
        // longer part of the contract, only that notify and cancel agree on it.
        const val NOTIFICATION_ID = 3006090
        const val CHANNEL_ID = "app_notices"
    }

    @get:Rule
    val postNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        manager.cancel(NOTIFICATION_ID)
    }

    @Test
    fun notifyLocalPostsOnTheAppNoticesChannel() {
        notifyLocal(PlatformContext(context), NOTIFICATION_ID, "Test title", "Test message")
        val posted = awaitPostedNotification()
        assertNotNull(posted, "notification $NOTIFICATION_ID was not posted")
        assertEquals(CHANNEL_ID, posted.notification.channelId)
        assertEquals("Test title", posted.notification.extras.getCharSequence("android.title"))
        assertEquals("Test message", posted.notification.extras.getCharSequence("android.text"))
    }

    @Test
    fun cancelNotifyLocalRemovesThePostedNotification() {
        notifyLocal(PlatformContext(context), NOTIFICATION_ID, "Test title", "Test message")
        assertNotNull(awaitPostedNotification(), "notification $NOTIFICATION_ID was not posted")

        cancelNotifyLocal(PlatformContext(context), NOTIFICATION_ID)
        assertTrue(awaitNotificationGone(), "notification $NOTIFICATION_ID should have been cancelled")
    }

    @Test
    fun cancelNotifyLocalOnUnknownIdIsANoOp() {
        // The STT prompt cancels before ever having posted on most paths;
        // this must never throw or disturb other notifications.
        cancelNotifyLocal(PlatformContext(context), NOTIFICATION_ID)
    }

    // notify() hands off to the notification service asynchronously; poll
    // briefly instead of asserting on an instant snapshot.
    private fun awaitPostedNotification(): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    // cancel() is just as asynchronous as notify().
    private fun awaitNotificationGone(): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (manager.activeNotifications.none { it.id == NOTIFICATION_ID }) return true
            Thread.sleep(100)
        }
        return false
    }
}
