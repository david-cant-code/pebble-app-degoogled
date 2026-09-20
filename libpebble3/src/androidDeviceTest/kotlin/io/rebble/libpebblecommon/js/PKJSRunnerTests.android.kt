package io.rebble.libpebblecommon.js

import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.pkjs.FixedNetworkDenyEnforcement
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.database.dao.FakeLockerEntryDao
import io.rebble.libpebblecommon.database.dao.FakeTimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.FakeTimelineReminderRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

fun createJsRunner(
    libPebble: LibPebble,
    scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    device: CompanionAppDevice,
    urlOpenRequests: Channel<String>,
    logMessages: Channel<String>,
    watchappPermissions: WatchappPermissionResolver,
    networkDenyEnforcement: NetworkDenyEnforcement = FixedNetworkDenyEnforcement(primaryLayerActive = true),
): JsRunner {
    val context = InstrumentationRegistry.getInstrumentation().context
    val configFlow = MutableStateFlow(LibPebbleConfig())
    val watchConfigFlow = WatchConfigFlow(configFlow)
    // Remote timeline emulation is off in the default config, so the emulator's DAOs
    // are never reached; HttpInterceptorManager requires the concrete type.
    val emulator = RemoteTimelineEmulator(
        watchConfigFlow,
        Json,
        FakeTimelinePinRealDao(),
        FakeTimelineReminderRealDao(),
    )
    return WebViewJsRunner(
        appContext = AppContext(context),
        libPebble = libPebble,
        jsTokenUtil = JsTokenUtil(
            object : TokenProvider {
                override suspend fun getDevToken(): String? {
                    return null
                }
            },
            lockerEntryDao = FakeLockerEntryDao(),
            watchConfigFlow = watchConfigFlow,
        ),
        device = device,
        appInfo = appInfo,
        lockerEntry = lockerEntry,
        jsPath = jsPath,
        urlOpenRequests = urlOpenRequests,
        logMessages = logMessages,
        scope = scope,
        remoteTimelineEmulator = emulator,
        httpInterceptorManager = HttpInterceptorManager(emulator, InjectedPKJSHttpInterceptors(emptyList())),
        notificationConfigFlow = NotificationConfigFlow(configFlow),
        watchappPermissions = watchappPermissions,
        networkDenyEnforcement = networkDenyEnforcement,
    )
}

abstract class PKJSRunnerSharedTestsAndroid(networkGranted: Boolean): PKJSRunnerTests(::createJsRunner, networkGranted) {
    @Test
    override fun testJSExecution() {
        super.testJSExecution()
    }

    @Test
    override fun testJSReady() {
        super.testJSReady()
    }

    @Test
    override fun testLocalStoragePersistence() {
        super.testLocalStoragePersistence()
    }

    @Test
    override fun testLocalStorageSandbox() {
        super.testLocalStorageSandbox()
    }

    @Test
    override fun testLocalStorageEarlyExecution() {
        super.testLocalStorageEarlyExecution()
    }

}

/** The shared runner tests in a network-denied session, which runs the app in the sandboxed frame. */
@MediumTest
class PKJSRunnerTestsAndroidNetworkDenied: PKJSRunnerSharedTestsAndroid(networkGranted = false)

@MediumTest
class PKJSRunnerTestsAndroid: PKJSRunnerSharedTestsAndroid(networkGranted = true) {
    @Test
    fun rendererExitLeavesAStoppableSessionWithNetworkGranted() = rendererExitCase(networkGranted = true)

    @Test
    fun rendererExitLeavesAStoppableSessionWithNetworkDenied() = rendererExitCase(networkGranted = false)

    // chrome://crash is the platform's documented way to end the renderer in a test
    // (android16-release, WebViewClient.java, onRenderProcessGone). Reaching the assertions at
    // all shows the process outlived the exit.
    @OptIn(DelicateCoroutinesApi::class)
    private fun rendererExitCase(networkGranted: Boolean) = runBlocking {
        val uuid = Uuid.random()
        val runner = makeRunner("", uuid, networkGranted = networkGranted) as WebViewJsRunner
        runner.start()
        withTimeout(5.seconds) { runner.readyState.first { it } }
        // stop() waits on the localStorage persist only once the restore has completed.
        withTimeout(5.seconds) {
            while (runner.evalWithResult("window.__localStorageShimmed === true;") != "true") delay(20)
        }

        runner.loadUrlForTest("chrome://crash")
        withTimeout(10.seconds) {
            while (!runner.rendererGone) delay(20)
        }
        assertFalse(runner.readyState.value)
        runner.eval("window.test = true;")
        // stop() runs its teardown NonCancellable, so a timeout around it could not fire.
        val stopJob = GlobalScope.launch { runner.stop() }
        withTimeout(10.seconds) { stopJob.join() }

        val second = makeRunner("", uuid, networkGranted = networkGranted)
        second.start()
        withTimeout(5.seconds) { second.readyState.first { it } }
        second.stop()
    }
}