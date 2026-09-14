package coredevices.coreapp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import io.rebble.libpebblecommon.pebblekit.two.PebbleKit2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives a real PebbleKit 2 session against a listener service the test APK exports, so the
 * session's outbound binding is observed from the companion's side. Pins the toggle at the
 * session: a session started while PebbleKit 2 is off binds out to nothing, at start and at
 * stop, and one started while it is on sends the companion APP_OPENED and, on stop,
 * APP_CLOSED. The session reads the toggle from the app's config, so the test flips the real
 * config and restores it.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKit2SessionTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKit2SessionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val libPebble: LibPebble = GlobalContext.get().get()
    private lateinit var original: LibPebbleConfig
    private var connectionScope: ConnectionCoroutineScope? = null
    private var libPebbleScope: LibPebbleCoroutineScope? = null

    /** The actions the listener service reported, in order; it runs in another process. */
    private val actions = LinkedBlockingQueue<String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(RecordingPebbleKit2ListenerService.EXTRA_ACTION)?.let(actions::put)
        }
    }

    @Before
    fun snapshot() {
        original = libPebble.config.value
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(RecordingPebbleKit2ListenerService.ACTION_RECORDED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    @After
    fun restore() {
        context.unregisterReceiver(receiver)
        connectionScope?.cancel()
        libPebbleScope?.cancel()
        libPebble.updateConfig(original)
    }

    @Test
    fun bindsOutToTheCompanionWhilePebbleKit2IsOn() {
        setPebbleKit2(true)
        val session = startSession()
        assertEquals("APP_OPENED", actions.poll(10, TimeUnit.SECONDS))

        runBlocking { session.stop() }
        assertEquals("APP_CLOSED", actions.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun bindsOutToNothingWhilePebbleKit2IsOff() {
        setPebbleKit2(false)
        val session = startSession()
        assertNull(
            actions.poll(3, TimeUnit.SECONDS),
            "the session reached the companion while PebbleKit 2 is off",
        )

        runBlocking { session.stop() }
        assertNull(
            actions.poll(3, TimeUnit.SECONDS),
            "stopping the session reached the companion while PebbleKit 2 is off",
        )
    }

    private fun setPebbleKit2(enabled: Boolean) {
        val config = libPebble.config.value
        libPebble.updateConfig(config.copy(watchConfig = config.watchConfig.copy(pebbleKit2Enabled = enabled)))
    }

    private fun startSession(): PebbleKit2 {
        val device = CompanionAppDevice(
            identifier = object : PebbleIdentifier {
                override val asString = "test-watch"
            },
            watchInfo = testWatchInfo(),
            appMessages = RecordingWatch(),
        )
        val connection = ConnectionCoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lib = LibPebbleCoroutineScope(SupervisorJob() + Dispatchers.Default)
        connectionScope = connection
        libPebbleScope = lib
        val session = PebbleKit2(device, appInfo(), pkjsRunning = false, lib, connection)
        runBlocking { session.start(emptyFlow()) }
        return session
    }

    /** A watchapp whose declared companion is this test package. */
    private fun appInfo() = PbwAppInfo(
        uuid = "864369ab-1f37-4a2e-9243-dd6b21af9c14",
        shortName = "test",
        versionLabel = "1.0",
        resources = Resources(),
        companionApp = CompanionApp(
            android = AndroidCompanionAppRoot(
                apps = listOf(AndroidCompanionAppInstance(pkg = instrumentation.context.packageName)),
            ),
        ),
    )
}
