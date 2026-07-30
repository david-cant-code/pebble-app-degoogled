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

/**
 * Pins the fork's notifyLocal actual, the replacement for the deleted
 * kmpnotifier local-notification path. Its only production caller is the
 * Cactus incompatible-model nudge, which is dead on a clean install (no
 * downloaded models) and only fires in the field on a weights-version bump,
 * with failures swallowed by the caller's catch, so nothing else would
 * surface a broken implementation before it mattered.
 */
class LocalNotifyTest {
    private companion object {
        // Pinned against LocalNotify.android.kt's private constants: the id
        // dedupes repeat nudges and the channel is the user-facing surface
        // where the notification can be muted.
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
        notifyLocal(PlatformContext(context), "Test title", "Test message")
        val posted = awaitPostedNotification()
        assertNotNull(posted, "notification $NOTIFICATION_ID was not posted")
        assertEquals(CHANNEL_ID, posted.notification.channelId)
        assertEquals("Test title", posted.notification.extras.getCharSequence("android.title"))
        assertEquals("Test message", posted.notification.extras.getCharSequence("android.text"))
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
}
