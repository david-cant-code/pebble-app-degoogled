package com.anopticlabs.gravel.pkjs

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source sentinel for how `WebViewJsRunner` ends a session: the end of a session built as
 * granted once its grant is denied, the bounded localStorage save, and the dead state.
 * GrantedSessionDenialTest checks the end, the bound and the single save on a device, which CI
 * does not run; the dead state's location watches and the quiet returns are checked here alone.
 *
 * The checks match text with line comments removed, and take a function's body, or a block's, by
 * the indentation of its closing brace. Text inside a block comment would satisfy them.
 */
class SessionEndSentinelTest {

    private val source by lazy {
        TrackedTree.file("libpebble3/src/androidMain/kotlin/io/rebble/libpebblecommon/js/WebViewJsRunner.kt").readText()
    }

    private val collectorEnd =
        Regex("""(?m)^ {20}applyNetworkProxy\(allowed\)\n {20}if \(([^\n]*)\) endGrantedSessionOnDenial\(\)$""")
    private val nonCancellableBlock =
        Regex("""(?m)^ {8}withContext\(NonCancellable \+ Dispatchers\.Main\) \{\n(.*?)\n {8}\}$""", RegexOption.DOT_MATCHES_ALL)
    private val endingFlag = Regex("""(?m)^ {12}ending = true$""")
    private val readinessDrop = Regex("""(?m)^ {12}_readyState\.value = false$""")
    private val guardedSave = Regex("""(?m)^ {12}if \(restoreCompleted\) persistLocalStorage\(\)$""")
    private val end = Regex("""(?m)^ {12}webView\?\.let \{ endSession\(it\) \}$""")
    private val boundedWait =
        Regex("""(?m)^ {8}val saved = withTimeoutOrNull\(PERSIST_TIMEOUT\) \{\n {12}suspendCancellableCoroutine \{""")
    private val loadAppReturn = Regex("""(?m)^ {8}if \(webView == null\) return$""")

    private val noCollectorEnd = "start()'s collector no longer calls endGrantedSessionOnDenial right after its proxy call"
    private val wrongCondition = "the collector's end no longer applies to exactly a denied grant in a granted construction"
    private val cancellable = "endGrantedSessionOnDenial no longer runs its body NonCancellable on the main thread"
    private val noEndingFlag = "endGrantedSessionOnDenial no longer marks the session as ending before it saves"
    private val readyWhileSaving = "endGrantedSessionOnDenial no longer marks the session not ready before it saves"
    private val noSave = "endGrantedSessionOnDenial no longer saves localStorage"
    private val unguardedSave = "endGrantedSessionOnDenial saves without the restore having completed"
    private val endBeforeSave = "endGrantedSessionOnDenial no longer ends the session after the save"
    private val unbounded = "persistLocalStorage no longer bounds its wait by PERSIST_TIMEOUT"
    private val noJoin = "stop() no longer waits for the collector before its own save"
    private val noStopSave = "stop() no longer saves localStorage"
    private val nativeClearInSave = "persistLocalStorage's script changes the native store outside saveState"
    private val noEndWatches = "endSession no longer ends the session's location watches"
    private val readyWhileEnding = "markReadyUnlessEnded can mark an ending session ready"
    private val loadAppThrows = "loadApp no longer returns when the session has ended"
    private val loadAppJsThrows = "loadAppJs no longer returns when the session has ended"

    private fun body(code: String, declaration: String): String? =
        Regex(Regex.escape(declaration) + """ \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(code)?.groupValues?.get(1)

    /** The message of every check that [text] fails. */
    private fun problems(text: String): List<String> {
        val code = text.lines().joinToString("\n") { it.substringBefore("//") }
        val endFunction = body(code, "private suspend fun endGrantedSessionOnDenial()")
            ?: return listOf("WebViewJsRunner no longer has endGrantedSessionOnDenial")
        return buildList {
            val call = collectorEnd.find(code)
            if (call == null) add(noCollectorEnd)
            else if (call.groupValues[1] != "!allowed && !denyConstruction") add(wrongCondition)

            val block = nonCancellableBlock.find(endFunction)?.groupValues?.get(1)
            if (block == null) {
                add(cancellable)
            } else {
                val flag = endingFlag.find(block)
                val save = guardedSave.find(block)
                val ended = end.find(block)
                val drop = readinessDrop.find(block)
                if (flag == null || (save != null && flag.range.first > save.range.first)) add(noEndingFlag)
                if (drop == null || (save != null && drop.range.first > save.range.first)) add(readyWhileSaving)
                if (save == null) add(if ("persistLocalStorage()" in block) unguardedSave else noSave)
                if (ended == null || (save != null && ended.range.first < save.range.first)) add(endBeforeSave)
            }

            val persist = body(code, "private suspend fun persistLocalStorage()").orEmpty()
            if (!boundedWait.containsMatchIn(persist)) add(unbounded)
            val pageOnlyClear = "Storage.prototype.clear.call(window.localStorage);"
            if (pageOnlyClear !in persist || "window.localStorage.clear()" in persist) add(nativeClearInSave)
            val stop = body(code, "override suspend fun stop()").orEmpty()
            val join = stop.indexOf("networkPermissionCollector?.cancelAndJoin()")
            val stopSave = stop.indexOf("persistLocalStorage()")
            if (stopSave < 0) add(noStopSave) else if (join < 0 || join > stopSave) add(noJoin)
            if ("geolocationInterface.endWatches()" !in body(code, "private fun endSession(view: WebView)").orEmpty()) {
                add(noEndWatches)
            }
            if ("if (webView != null && !ending) _readyState.value = true" !in
                body(code, "private fun markReadyUnlessEnded()").orEmpty()
            ) {
                add(readyWhileEnding)
            }
            val loadApp = body(code, "private suspend fun loadApp(url: String)").orEmpty()
            if (!loadAppReturn.containsMatchIn(loadApp) || "check(webView != null)" in loadApp) add(loadAppThrows)
            val loadAppJs = body(code, "override suspend fun loadAppJs(jsUrl: String)").orEmpty()
            if ("} ?: logger.w {" !in loadAppJs || "?: error(" in loadAppJs) add(loadAppJsThrows)
        }
    }

    @Test
    fun theRunnerEndsSessionsAsDesigned() {
        assertEquals(emptyList(), problems(source))
    }

    private fun String.edited(old: String, new: String): String {
        assertTrue(contains(old), "the runner no longer holds the text this edit replaces: $old")
        assertEquals(1, split(old).size - 1, "the text this edit replaces is not unique: $old")
        return replace(old, new)
    }

    @Test
    fun knownBadEditsAreNoticed() {
        val proxy = "                    applyNetworkProxy(allowed)\n"
        val call = "                    if (!allowed && !denyConstruction) endGrantedSessionOnDenial()\n"
        val context = "withContext(NonCancellable + Dispatchers.Main) {\n            if (webView == null) return@withContext"
        val flag = "            ending = true\n"
        val save = "            if (restoreCompleted) persistLocalStorage()\n"
        val endLine = "            webView?.let { endSession(it) }\n"
        val edits: Map<String, Pair<String, (String) -> String>> = mapOf(
            "the collector no longer ends the session" to (noCollectorEnd to { it.edited(call, "") }),
            "a denied construction ends too" to
                (wrongCondition to { it.edited("if (!allowed && !denyConstruction) endG", "if (!allowed) endG") }),
            "the session ends before the proxy call" to (noCollectorEnd to { it.edited(proxy + call, call + proxy) }),
            "stop() can cancel the save" to
                (cancellable to { it.edited(context, context.replace("NonCancellable + ", "")) }),
            "the ending flag is dropped" to (noEndingFlag to { it.edited(flag, "") }),
            "the save is dropped" to (noSave to { it.edited(save, "") }),
            "readiness stays up during the save" to
                (readyWhileSaving to { it.edited(flag + "            _readyState.value = false\n", flag) }),
            "the save runs before the restore completed" to
                (unguardedSave to { it.edited(save, "            persistLocalStorage()\n") }),
            "the session ends before the save" to (endBeforeSave to { it.edited(save + endLine, endLine + save) }),
            "the save waits without bound" to
                (unbounded to { it.edited("withTimeoutOrNull(PERSIST_TIMEOUT) {", "withTimeoutOrNull(Duration.INFINITE) {") }),
            "stop() no longer saves" to
                (noStopSave to { it.edited("                    persistLocalStorage()\n                } else {", "                    Unit\n                } else {") }),
            "the save clears the native store first" to
                (nativeClearInSave to { it.edited("Storage.prototype.clear.call(window.localStorage);", "window.localStorage.clear();") }),
            "stop() cancels the collector without waiting" to
                (noJoin to { it.edited("networkPermissionCollector?.cancelAndJoin()", "networkPermissionCollector?.cancel()") }),
            "the dead state keeps location watches" to
                (noEndWatches to { it.edited("        geolocationInterface.endWatches()\n", "") }),
            "readiness ignores the ending flag" to
                (readyWhileEnding to { it.edited("webView != null && !ending) _readyState", "webView != null) _readyState") }),
            "loadApp throws on an ended session" to
                (loadAppThrows to { it.edited("        if (webView == null) return\n", "        check(webView != null) { \"WebView not initialized\" }\n") }),
            "loadAppJs throws on an ended session" to
                (loadAppJsThrows to { it.edited("} ?: logger.w { \"Not loading the app script", "} ?: error(\"Not loading the app script") }),
        )
        for ((name, case) in edits) {
            val (expected, edit) = case
            assertEquals(listOf(expected), problems(edit(source)), name)
        }
    }
}
