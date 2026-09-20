package io.rebble.libpebblecommon.js

import androidx.test.filters.MediumTest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The page construction of a network-denied session: a host page that holds no watchapp script,
 * the app in a sandboxed frame under it, and the two controls that keep the frame on the document
 * it was created with. Every navigation target is the session's own origin or a local scheme, so
 * nothing here reaches the network.
 */
@MediumTest
class PKJSDenyConstructionTest : PKJSRunnerTests(::createJsRunner, networkGrantedByDefault = false) {
    private val markerJs = "window.marker = 'set';"

    private suspend fun startedRunner(
        js: String = markerJs,
        uuid: Uuid = Uuid.random(),
        networkGranted: Boolean = false,
        urlOpenRequests: Channel<String> = Channel(Channel.UNLIMITED),
    ): WebViewJsRunner {
        val runner = makeRunner(js, uuid, networkGranted = networkGranted, urlOpenRequests = urlOpenRequests) as WebViewJsRunner
        runner.start()
        withTimeout(10.seconds) { runner.readyState.first { it } }
        return runner
    }

    private suspend fun WebViewJsRunner.inFrame(js: String): String =
        Json.decodeFromString<JsonElement>(evalWithResult(js) as String).jsonPrimitive.content

    private suspend fun WebViewJsRunner.inTopDocument(js: String): String =
        Json.decodeFromString<JsonElement>(evalInTopDocumentForTest(js)).jsonPrimitive.content

    // The commit guard posts the end of the session to the main thread, so the view goes a
    // moment after frameRecommitted is set.
    private suspend fun awaitNoWebView(runner: WebViewJsRunner) = withTimeout(5.seconds) {
        while (runner.webViewSettingsForTest() != null) delay(20)
    }

    // stop() runs its teardown NonCancellable, so a timeout around it could not fire.
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun stopWithinTimeout(runner: JsRunner) {
        val stopJob = GlobalScope.launch { runner.stop() }
        withTimeout(10.seconds) { stopJob.join() }
    }

    @Test
    fun topDocumentHoldsNoWatchappScript() = runBlocking {
        val runner = startedRunner()
        assertTrue(runner.denyConstruction)
        assertEquals("https://pkjs.gravel.invalid/host.html", runner.inTopDocument("document.URL"))
        assertEquals("1", runner.inTopDocument("document.scripts.length"))
        assertEquals("1", runner.inTopDocument("document.getElementsByTagName('iframe').length"))
        assertEquals("allow-scripts", runner.inTopDocument("document.getElementsByTagName('iframe')[0].getAttribute('sandbox')"))
        assertEquals("undefined", runner.inTopDocument("typeof window.marker"))
        assertEquals("undefined", runner.inTopDocument("typeof window.signalReady"))

        assertEquals("set", runner.inFrame("window.marker"))
        assertEquals("null", runner.inFrame("window.origin"))
        assertEquals("about:srcdoc", runner.inFrame("document.URL"))
        assertEquals("true", runner.inFrame("window.parent !== window"))
        assertEquals("function,function", runner.inFrame("typeof Pebble.sendAppMessage + ',' + typeof Pebble.addEventListener"))
        runner.stop()
    }

    @Test
    fun fileAccessIsOffOnlyInADenySession() = runBlocking {
        val denied = startedRunner()
        val deniedSettings = denied.webViewSettingsForTest()!!
        assertFalse(deniedSettings.allowFileAccess)
        assertFalse(deniedSettings.allowFileAccessFromFileURLs)
        assertFalse(deniedSettings.allowUniversalAccessFromFileURLs)
        assertFalse(deniedSettings.supportMultipleWindows())
        denied.stop()

        val allowed = startedRunner(networkGranted = true)
        assertFalse(allowed.denyConstruction)
        assertTrue(allowed.inTopDocument("document.URL").startsWith(WebViewJsRunner.STARTUP_URL))
        assertTrue(allowed.webViewSettingsForTest()!!.allowFileAccess)
        assertEquals("set", allowed.inTopDocument("window.marker"))
        allowed.stop()
    }

    @Test
    fun frameStorageWritesThroughWithoutAStop() = runBlocking {
        val uuid = Uuid.random()
        val first = startedRunner("localStorage.foo = 'bar'; localStorage.setItem('gone', '1'); delete localStorage.gone;", uuid)
        val second = startedRunner("", uuid)
        assertEquals("bar", second.inFrame("localStorage.foo"))
        assertEquals("""{"foo":"bar"}""", second.inFrame("JSON.stringify(localStorage)"))
        assertEquals("1", second.inFrame("localStorage.length"))
        assertEquals("function", second.inFrame("typeof localStorage.hasOwnProperty"))
        assertEquals("true", second.inFrame("localStorage.hasOwnProperty('foo')"))
        assertEquals("false", second.inFrame("localStorage.hasOwnProperty('gone')"))
        first.stop()
        second.stop()

        val allowed = startedRunner("", uuid, networkGranted = true)
        assertEquals("bar", allowed.inTopDocument("localStorage.getItem('foo')"))
        allowed.stop()
    }

    @Test
    fun frameTimersRunAtTheirInterval() = runBlocking {
        val runner = startedRunner("window.ticks = 0; setInterval(function () { window.ticks++; }, 100);")
        val before = runner.inFrame("window.ticks").toInt()
        delay(3000)
        val ticks = runner.inFrame("window.ticks").toInt() - before
        // 30 at the full rate; 3 when the frame's timers are held to one a second.
        assertTrue(ticks >= 20, "only $ticks ticks of a 100 ms interval in 3 s")
        runner.stop()
    }

    @Test
    fun settingsFlowRunsThroughTheFrame() = runBlocking {
        val js = """
            Pebble.addEventListener('showConfiguration', function () { Pebble.openURL('https://pkjs.gravel.invalid/config'); });
            Pebble.addEventListener('webviewclosed', function (e) { window.closedWith = e.response; });
        """.trimIndent()
        val urlOpenRequests = Channel<String>(Channel.UNLIMITED)
        val runner = startedRunner(js, urlOpenRequests = urlOpenRequests)
        runner.signalShowConfiguration()
        assertEquals("https://pkjs.gravel.invalid/config", withTimeout(5.seconds) { urlOpenRequests.receive() })
        val response = "{\"k\":\"a \\\" ' </script> \u2028\"}"
        runner.signalWebviewClosed(response)
        withTimeout(5.seconds) {
            while (runner.inFrame("typeof window.closedWith") != "string") delay(20)
        }
        assertEquals(response, runner.inFrame("window.closedWith"))
        runner.stop()
    }

    @Test
    fun navigationLockRefusesDataAndBlobNavigationsOfTheFrame() = runBlocking {
        val vectors = listOf(
            "location.href = 'data:text/html,<p>other</p>'",
            "location.href = URL.createObjectURL(new Blob(['<p>other</p>'], {type: 'text/html'}))",
        )
        for (vector in vectors) {
            val runner = startedRunner()
            val before = runner.refusedNavigationCount
            runner.eval(vector)
            withTimeout(5.seconds) {
                while (runner.refusedNavigationCount == before) delay(20)
            }
            delay(500)
            assertFalse(runner.frameRecommitted, vector)
            assertEquals("set", runner.inFrame("window.marker"), vector)
            assertEquals("about:srcdoc", runner.inFrame("document.URL"), vector)
            runner.stop()
        }
    }

    @Test
    fun commitGuardEndsTheSessionWhenTheFrameLoadsASecondDocument() = runBlocking {
        val vectors = listOf(
            "location.reload()",
            "location.href = 'about:srcdoc'",
            "location.href = 'https://pkjs.gravel.invalid/other'",
        )
        for (vector in vectors) {
            val uuid = Uuid.random()
            val runner = startedRunner(uuid = uuid)
            runner.eval(vector)
            withTimeout(5.seconds) {
                while (!runner.frameRecommitted) delay(20)
            }
            awaitNoWebView(runner)
            assertFalse(runner.readyState.value, vector)
            assertEquals("", runner.readAppScript(), vector)
            stopWithinTimeout(runner)

            val next = startedRunner(uuid = uuid)
            assertEquals("set", next.inFrame("window.marker"), vector)
            next.stop()
        }
    }

    @Test
    fun sessionWhoseAppScriptReloadsItsFrameAtLoadNeverBecomesReady() = runBlocking {
        val runner = makeRunner("location.reload();", Uuid.random()) as WebViewJsRunner
        runner.start()
        withTimeout(10.seconds) {
            while (!runner.frameRecommitted) delay(20)
        }
        awaitNoWebView(runner)
        assertFalse(runner.readyState.value)
        stopWithinTimeout(runner)
    }

    // Any script in the frame can call the bridge's ready confirmation, here in a stream that
    // runs across the end of the session.
    @Test
    fun readyConfirmationsDoNotReadyASessionTheCommitGuardEnded() = runBlocking {
        val runner = startedRunner()
        runner.eval(
            "_Pebble.frameDocumentLoaded(); " +
                "var end = Date.now() + 300; while (Date.now() < end) _Pebble.privateFnConfirmReadySignal(true);"
        )
        withTimeout(5.seconds) {
            while (!runner.frameRecommitted) delay(20)
        }
        awaitNoWebView(runner)
        delay(500)
        assertFalse(runner.readyState.value)
        stopWithinTimeout(runner)
    }

    @Test
    fun topDocumentStaysTheHostPage() = runBlocking {
        val runner = startedRunner()
        val before = runner.refusedNavigationCount
        runner.evalInTopDocumentForTest("location.href = 'https://pkjs.gravel.invalid/elsewhere'; 0")
        withTimeout(5.seconds) {
            while (runner.refusedNavigationCount == before) delay(20)
        }
        // The sandbox has no allow-top-navigation token, so the frame cannot even ask.
        assertEquals(
            "SecurityError",
            runner.inFrame("(function () { try { window.top.location = 'https://pkjs.gravel.invalid/elsewhere'; return 'no error'; } catch (e) { return e.name; } })()"),
        )
        delay(500)
        assertEquals("https://pkjs.gravel.invalid/host.html", runner.inTopDocument("document.URL"))
        assertEquals("set", runner.inFrame("window.marker"))
        runner.stop()
    }

    @Test
    fun frameCannotOpenWindowsOrSubmitForms() = runBlocking {
        val runner = startedRunner()
        assertEquals("true", runner.inFrame("window.open('https://pkjs.gravel.invalid/popup') === null"))
        runner.eval(
            """
            (function () {
                var form = document.createElement('form');
                form.action = 'https://pkjs.gravel.invalid/form';
                document.body.appendChild(form);
                form.submit();
            })();
            """.trimIndent()
        )
        delay(1000)
        assertFalse(runner.frameRecommitted)
        assertEquals("about:srcdoc", runner.inFrame("document.URL"))
        assertEquals("set", runner.inFrame("window.marker"))
        runner.stop()
    }
}
