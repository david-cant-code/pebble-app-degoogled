package coredevices.coreapp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ACTION = "coredevices.coreapp.test.TARGETED_BROADCAST"
private const val DELIVERED_TIMEOUT_SECONDS = 5L

/**
 * Verifies the platform behaviour the classic PebbleKit broadcast narrowing depends on.
 *
 * Classic PebbleKit clients register their receivers at runtime rather than in a manifest, so
 * restricting delivery with Intent.setPackage is only useful if it also constrains dynamically
 * registered receivers. If it did not, the narrowing would be inert and watch data would still
 * reach every app while the code looked like it had been fixed. This asserts the behaviour
 * directly instead of assuming it.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.TargetedBroadcastDeliveryTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class TargetedBroadcastDeliveryTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun untargetedBroadcastReachesARuntimeReceiver() {
        assertTrue(
            sendAndAwait { it },
            "baseline failed: an untargeted broadcast did not reach the receiver at all",
        )
    }

    @Test
    fun broadcastTargetedAtThisPackageIsDelivered() {
        // The companion case: a declared companion must still receive its watch data.
        assertTrue(
            sendAndAwait { it.setPackage(context.packageName) },
            "a broadcast targeted at this package was not delivered",
        )
    }

    @Test
    fun broadcastTargetedElsewhereIsNotDelivered() {
        // The case that matters: an app that is not a declared companion must see nothing.
        assertFalse(
            sendAndAwait { it.setPackage("com.example.not.this.package") },
            "setPackage did not constrain delivery, so narrowing the broadcast is inert",
        )
    }

    /** Returns whether a broadcast built by [configure] reached a runtime-registered receiver. */
    private fun sendAndAwait(configure: (Intent) -> Intent): Boolean {
        val delivered = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = delivered.countDown()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            context.sendOrderedBroadcast(configure(Intent(ACTION)), null)
            return delivered.await(DELIVERED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
