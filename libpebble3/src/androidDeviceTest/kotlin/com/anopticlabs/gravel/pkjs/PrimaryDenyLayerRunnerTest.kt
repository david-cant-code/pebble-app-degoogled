package com.anopticlabs.gravel.pkjs

import androidx.test.filters.MediumTest
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.PKJSRunnerTests
import io.rebble.libpebblecommon.js.WebViewJsRunner
import io.rebble.libpebblecommon.js.createJsRunner
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Runners whose embedding app reports enforcement, with the switch for Network-denied sessions
// set to switchOn.
private fun runners(enforcement: NetworkDenyEnforcement, switchOn: Boolean): (
    LibPebble, CoroutineScope, PbwAppInfo, LockerEntry, Path, CompanionAppDevice, Channel<String>, Channel<String>,
    WatchappPermissionResolver,
) -> JsRunner = { libPebble, scope, appInfo, lockerEntry, jsPath, device, urlOpenRequests, logMessages, watchappPermissions ->
    val base = libPebble.config.value
    val switched = object : LibPebble by libPebble {
        override val config = MutableStateFlow(
            base.copy(watchConfig = base.watchConfig.copy(deniedPkjsWithoutPrimaryLayer = switchOn)),
        )
    }
    createJsRunner(
        switched, scope, appInfo, lockerEntry, jsPath, device, urlOpenRequests, logMessages,
        watchappPermissions, enforcement,
    )
}

abstract class DenyLayerRunnerTests(enforcement: NetworkDenyEnforcement, switchOn: Boolean) :
    PKJSRunnerTests(runners(enforcement, switchOn)) {

    protected fun aDeniedSessionLoadsNoPageCase(): Unit = runBlocking {
        val runner = makeRunner("", Uuid.random(), networkGranted = false) as WebViewJsRunner
        runner.start()
        assertNull(withTimeoutOrNull(5.seconds) { runner.readyState.first { it } }, "the session became ready")
        assertEquals("\"about:blank\"", runner.evalInTopDocumentForTest("document.URL"))
        runner.stop()
    }

    protected fun aDeniedSessionRunsOnTheDenyHostPageCase(): Unit = runBlocking {
        val runner = makeRunner("", Uuid.random(), networkGranted = false) as WebViewJsRunner
        runner.start()
        withTimeout(10.seconds) { runner.readyState.first { it } }
        assertEquals("\"$DENY_HOST_PAGE_URL\"", runner.evalInTopDocumentForTest("document.URL"))
        runner.stop()
    }
}

/** The primary layer inactive where the switch applies, with the switch off. */
@MediumTest
class PrimaryDenyLayerRunnerTest : DenyLayerRunnerTests(
    FixedNetworkDenyEnforcement(primaryLayerActive = false, switchMayRunDeniedPkjs = true),
    switchOn = false,
) {
    @Test
    fun aDeniedSessionLoadsNoPage() = aDeniedSessionLoadsNoPageCase()

    @Test
    fun aGrantedSessionStillRuns() = runBlocking {
        val runner = makeRunner("", Uuid.random(), networkGranted = true)
        runner.start()
        withTimeout(10.seconds) { runner.readyState.first { it } }
        runner.stop()
    }
}

@MediumTest
class PrimaryDenyLayerSwitchOnRunnerTest : DenyLayerRunnerTests(
    FixedNetworkDenyEnforcement(primaryLayerActive = false, switchMayRunDeniedPkjs = true),
    switchOn = true,
) {
    @Test
    fun aDeniedSessionRunsOnTheDenyHostPage() = aDeniedSessionRunsOnTheDenyHostPageCase()
}

/** The primary layer inactive where the switch does not apply, as after a recorded install. */
@MediumTest
class PrimaryDenyLayerSwitchNotApplyingRunnerTest : DenyLayerRunnerTests(
    FixedNetworkDenyEnforcement(primaryLayerActive = false),
    switchOn = true,
) {
    @Test
    fun aDeniedSessionLoadsNoPageWithTheSwitchOn() = aDeniedSessionLoadsNoPageCase()
}

@MediumTest
class PrimaryDenyLayerActiveSwitchOffRunnerTest : DenyLayerRunnerTests(
    FixedNetworkDenyEnforcement(primaryLayerActive = true),
    switchOn = false,
) {
    @Test
    fun aDeniedSessionRunsOnTheDenyHostPage() = aDeniedSessionRunsOnTheDenyHostPageCase()
}
