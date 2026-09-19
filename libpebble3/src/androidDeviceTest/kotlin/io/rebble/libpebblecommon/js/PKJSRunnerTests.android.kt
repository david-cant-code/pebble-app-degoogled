package io.rebble.libpebblecommon.js

import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import org.junit.Test

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
    )
}

@MediumTest
class PKJSRunnerTestsAndroid: PKJSRunnerTests(::createJsRunner) {
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