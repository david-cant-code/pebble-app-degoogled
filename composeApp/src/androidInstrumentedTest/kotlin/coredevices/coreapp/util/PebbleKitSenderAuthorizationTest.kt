package coredevices.coreapp.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the AuthorizingBinder in [io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver]
 * through a real bind to the exported service. This process is not a declared companion of any
 * watchapp, so a START_APP or STOP_APP request must be denied, and the denial contract is an
 * EMPTY reply bundle: a request waved through to the library instead produces a reply carrying
 * the transmission-results key (even with zero watches connected), so an inverted or removed
 * authorization check turns these tests into failures rather than passing silently.
 *
 * The request keys duplicated here are the wire protocol pinned by
 * PebbleKitRequestProtocolTest; if that pin moves, this moves with it.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitSenderAuthorizationTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitSenderAuthorizationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var connection: ServiceConnection? = null

    @After
    fun unbind() {
        connection?.let { context.unbindService(it) }
        connection = null
    }

    @Test
    fun startAppFromANonCompanionGetsTheEmptyDenialReply() {
        assertDenied("START_APP")
    }

    @Test
    fun stopAppFromANonCompanionGetsTheEmptyDenialReply() {
        assertDenied("STOP_APP")
    }

    private fun assertDenied(action: String) {
        val service = bindSender()
        val request = Bundle().apply {
            putString("ACTION", action)
            putString("WATCHAPP_UUID", "864369ab-1f37-4a2e-9243-dd6b21af9c14")
        }

        val replied = CountDownLatch(1)
        var reply: Bundle? = null
        service.request(request, object : SendDataCallback.Stub() {
            override fun onResult(result: Bundle) {
                reply = result
                replied.countDown()
            }
        })

        // The reply is synchronous on the denial path, but nothing in the contract promises
        // that, so wait rather than assert immediately.
        assertTrue(replied.await(10, TimeUnit.SECONDS), "the $action request got no reply")
        val keys = reply!!.keySet()
        assertTrue(
            keys.isEmpty(),
            "denied $action must get the empty reply, but the reply carried $keys, " +
                "which means the request reached the library undenied",
        )
    }

    private fun bindSender(): UniversalRequestResponse {
        val bound = CountDownLatch(1)
        var binder: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent().setComponent(
            ComponentName(
                context,
                "io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver",
            )
        )
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            fail("could not bind io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver")
        }
        connection = conn
        assertTrue(bound.await(10, TimeUnit.SECONDS), "service connection timed out")
        return UniversalRequestResponse.Stub.asInterface(binder!!)
    }
}
