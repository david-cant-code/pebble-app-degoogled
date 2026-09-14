package coredevices.coreapp.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val CLASSIC_PROVIDER = "io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider"
private const val PK2_PROVIDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider"
private const val PK2_SERVICE = "io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver"

/**
 * Drives the entry-point gates of the two PebbleKit toggles through the real exported
 * components. The test process shares the app's process and Koin, so the toggles are flipped
 * through the real config and restored afterwards, and every flip waits for the component
 * state to land so the in-code gate and the system's refusal are not raced against each other.
 *
 * The sender service's toggle gate is per request, on the binder it hands out (see
 * PebbleSenderReceiver.onBind). The bind-while-off case forces the component back to its
 * default so the bind reaches the service at all. A refused request gets the empty reply; a
 * passed SEND_DATA_TO_WATCH gets the transmission-results key even with no watch connected.
 * The request keys duplicated here are pinned by PebbleKitRequestProtocolTest.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitToggleGateTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitToggleGateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val libPebble: LibPebble = GlobalContext.get().get()
    private lateinit var original: LibPebbleConfig
    private var connection: ServiceConnection? = null

    @Before
    fun snapshotConfig() {
        original = libPebble.config.value
    }

    @After
    fun restore() {
        connection?.let { context.unbindService(it) }
        connection = null
        libPebble.updateConfig(original)
        // The app's collector re-applies the component state asynchronously, and only when the
        // toggles changed; a component this test forced is put back by hand, so the next test
        // class starts from the state the config says.
        val classicOn = original.watchConfig.classicPebbleKitEnabled
        val pebbleKit2On = original.watchConfig.pebbleKit2Enabled
        setComponentEnabled(CLASSIC_PROVIDER, classicOn)
        setComponentEnabled(PK2_PROVIDER, pebbleKit2On)
        setComponentEnabled(PK2_SERVICE, pebbleKit2On)
        awaitComponentState(CLASSIC_PROVIDER, enabled = classicOn)
        awaitComponentState(PK2_PROVIDER, enabled = pebbleKit2On)
        awaitComponentState(PK2_SERVICE, enabled = pebbleKit2On)
    }

    @Test
    fun classicProviderAnswersOnlyWhileClassicIsOn() {
        setToggles(classic = false)
        assertNull(queryBasalt(), "the basalt provider answered while classic PebbleKit is off")

        setToggles(classic = true)
        val cursor = queryBasalt()
        assertNotNull(cursor, "the basalt provider did not answer while classic PebbleKit is on")
        cursor.close()

        setToggles(classic = false)
        assertNull(queryBasalt(), "the basalt provider kept answering after classic PebbleKit went off")
    }

    @Test
    fun senderRefusesRequestsOnAHeldBinderWhilePebbleKit2IsOff() {
        setToggles(pebbleKit2 = true)
        val service = bindSender()
        assertTrue(sendData(service).isNotEmpty(), "a request while PebbleKit 2 is on got the empty reply")

        setToggles(pebbleKit2 = false)
        assertTrue(
            sendData(service).isEmpty(),
            "a request on a binder held across the toggle-off reached the library",
        )

        setToggles(pebbleKit2 = true)
        assertTrue(sendData(service).isNotEmpty(), "a request after the toggle came back on stayed refused")
    }

    @Test
    fun aRefusedRequestOnAHeldBinderIsNotRead() {
        setToggles(pebbleKit2 = true)
        val service = bindSender()
        setToggles(pebbleKit2 = false)

        // Reading one extra of this Bundle throws in the reading process, so a refusal that
        // reads the request first ends the call instead of replying (KNOWN_ISSUES).
        val reply = try {
            requestRaw(service, unreadableRequest())
        } catch (e: RuntimeException) {
            fail("the request was read before it was refused: $e")
        }
        assertTrue(reply.isEmpty(), "a request refused while PebbleKit 2 is off came back with data")
    }

    @Test
    fun senderRefusesRequestsOnABindMadeWhilePebbleKit2IsOff() {
        setToggles(pebbleKit2 = false)
        // Toggle off, component enabled: the state during the second before the app's deferred
        // component teardown, and the state a service record survives in.
        setComponentEnabled(PK2_SERVICE, true)
        awaitComponentState(PK2_SERVICE, enabled = true)

        val service = bindSender()
        assertTrue(sendData(service).isEmpty(), "a request on a bind made while PebbleKit 2 is off reached the library")
    }

    @Test
    fun aRefusedRequestLogsNoneOfTheCallersStrings() {
        setToggles(pebbleKit2 = true)
        val service = bindSender()
        val capture = SenderLogCapture()
        val writersBefore = Logger.config.logWriterList
        Logger.addLogWriter(capture)
        try {
            // This app is no companion of any watchapp, so its START is refused as unauthorized.
            val reply = request(service, action = "START_APP", watchapp = "not a uuid\n$FORGED_LOG_LINE")
            assertTrue(reply.isEmpty(), "an unauthorized START was not refused")
        } finally {
            Logger.setLogWriters(writersBefore)
        }
        assertTrue(capture.senderMessages.isNotEmpty(), "the refusal logged nothing, so this test cannot see what it would log")
        assertTrue(capture.messages.none { FORGED_LOG_LINE in it }, "a caller's request string reached the log: ${capture.messages}")
    }

    private fun setToggles(classic: Boolean? = null, pebbleKit2: Boolean? = null) {
        val config = libPebble.config.value
        val classicOn = classic ?: config.watchConfig.classicPebbleKitEnabled
        val pebbleKit2On = pebbleKit2 ?: config.watchConfig.pebbleKit2Enabled
        libPebble.updateConfig(
            config.copy(
                watchConfig = config.watchConfig.copy(
                    classicPebbleKitEnabled = classicOn,
                    pebbleKit2Enabled = pebbleKit2On,
                ),
            ),
        )
        awaitComponentState(CLASSIC_PROVIDER, enabled = classicOn)
        awaitComponentState(PK2_PROVIDER, enabled = pebbleKit2On)
        awaitComponentState(PK2_SERVICE, enabled = pebbleKit2On)
    }

    private fun setComponentEnabled(className: String, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, className),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** The app's component-state collector applies a flip asynchronously; wait for it. */
    private fun awaitComponentState(className: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, className)
        val deadline = System.currentTimeMillis() + 5000
        while (
            (context.packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED) == enabled
        ) {
            if (System.currentTimeMillis() > deadline) {
                fail("$className did not become ${if (enabled) "enabled" else "disabled"} in time")
            }
            Thread.sleep(50)
        }
    }

    private fun queryBasalt() = context.contentResolver.query(
        Uri.parse("content://com.getpebble.android.provider.basalt/state"),
        null, null, null, null,
    )

    private fun bindSender(): UniversalRequestResponse {
        val bound = CountDownLatch(1)
        var binder: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}

            override fun onNullBinding(name: ComponentName?) {
                bound.countDown()
            }
        }
        val intent = Intent().setComponent(ComponentName(context, PK2_SERVICE))
        val accepted = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        // Registered whatever the result: a refused bind still leaves the connection registered
        // in this process until it is unbound.
        connection = conn
        assertTrue(accepted, "the system refused the bind; the sender service component is not enabled")
        assertTrue(bound.await(10, TimeUnit.SECONDS), "service connection timed out")
        return UniversalRequestResponse.Stub.asInterface(
            binder ?: fail("the sender service handed out no binder"),
        )
    }

    /** The reply's keys for a SEND_DATA_TO_WATCH request; empty means the gate refused it. */
    private fun sendData(service: UniversalRequestResponse): Set<String> =
        request(service, action = "SEND_DATA_TO_WATCH", watchapp = "864369ab-1f37-4a2e-9243-dd6b21af9c14") {
            putBundle("DATA_DICTIONARY", Bundle())
        }

    private fun request(
        service: UniversalRequestResponse,
        action: String,
        watchapp: String,
        extras: Bundle.() -> Unit = {},
    ): Set<String> = requestRaw(
        service,
        Bundle().apply {
            putString("ACTION", action)
            putString("WATCHAPP_UUID", watchapp)
            extras()
        },
    )

    /** A single entry whose value type code the platform does not know: reading any extra throws. */
    private fun unreadableRequest(): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0) // bundle length, patched below
            parcel.writeInt(BUNDLE_MAGIC)
            val start = parcel.dataPosition()
            parcel.writeInt(1) // entry count
            parcel.writeString("ACTION")
            parcel.writeInt(UNKNOWN_VALUE_TYPE)
            val end = parcel.dataPosition()
            parcel.setDataPosition(0)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
            parcel.writeInt(0) // the has-intent flag android16-release BaseBundle.readFromParcelInner reads after the map
            parcel.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun requestRaw(service: UniversalRequestResponse, request: Bundle): Set<String> {
        val replied = CountDownLatch(1)
        var reply: Bundle? = null
        service.request(request, object : SendDataCallback.Stub() {
            override fun onResult(result: Bundle) {
                reply = result
                replied.countDown()
            }
        })
        assertTrue(replied.await(10, TimeUnit.SECONDS), "the request got no reply")
        return reply!!.keySet()
    }

    /**
     * Every message, whatever its tag: a caller's string logged by another logger on the refusal
     * path would go unseen by a check that kept only this file's tag.
     */
    private class SenderLogCapture : LogWriter() {
        val messages = CopyOnWriteArrayList<String>()
        val senderMessages = CopyOnWriteArrayList<String>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            messages += message
            if (tag == "PebbleSenderReceiver") senderMessages += message
        }
    }

    private companion object {
        const val FORGED_LOG_LINE = "2026-01-01T00:00:00Z [E] Forged: planted by the caller"
        const val BUNDLE_MAGIC = 0x4C444E42 // 'B' 'N' 'D' 'L'
        const val UNKNOWN_VALUE_TYPE = 1_000_000
    }
}
