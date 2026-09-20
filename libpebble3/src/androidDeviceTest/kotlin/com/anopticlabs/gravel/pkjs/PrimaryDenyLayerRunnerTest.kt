package com.anopticlabs.gravel.pkjs

import androidx.test.filters.MediumTest
import io.rebble.libpebblecommon.js.PKJSRunnerTests
import io.rebble.libpebblecommon.js.WebViewJsRunner
import io.rebble.libpebblecommon.js.createJsRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Runners whose embedding app reports that the primary deny layer is not active. */
@MediumTest
class PrimaryDenyLayerRunnerTest : PKJSRunnerTests(
    { libPebble, scope, appInfo, lockerEntry, jsPath, device, urlOpenRequests, logMessages, watchappPermissions ->
        createJsRunner(
            libPebble, scope, appInfo, lockerEntry, jsPath, device, urlOpenRequests, logMessages,
            watchappPermissions, FixedNetworkDenyEnforcement(primaryLayerActive = false),
        )
    },
) {
    @Test
    fun aDeniedSessionLoadsNoPage() = runBlocking {
        val runner = makeRunner("", Uuid.random(), networkGranted = false) as WebViewJsRunner
        runner.start()
        assertNull(withTimeoutOrNull(5.seconds) { runner.readyState.first { it } }, "the session became ready")
        assertEquals("\"about:blank\"", runner.evalInTopDocumentForTest("document.URL"))
        runner.stop()
    }

    @Test
    fun aGrantedSessionStillRuns() = runBlocking {
        val runner = makeRunner("", Uuid.random(), networkGranted = true)
        runner.start()
        withTimeout(10.seconds) { runner.readyState.first { it } }
        runner.stop()
    }
}
