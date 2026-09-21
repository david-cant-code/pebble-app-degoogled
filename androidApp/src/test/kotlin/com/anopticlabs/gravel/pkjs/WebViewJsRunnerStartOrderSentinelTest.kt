package com.anopticlabs.gravel.pkjs

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source sentinel for the statement order in `WebViewJsRunner.start()`: the `shouldRunPkjs` gate's
 * return, then the proxy override, then the app load. A device test cannot read the override
 * back: ProxyController has no getter for it (androidx.webkit 1.16.0, ProxyController).
 *
 * The checks match text inside `start()` with line comments removed, and take a statement of
 * `start()`'s own block by its indentation. Text inside a block comment would satisfy them.
 */
class WebViewJsRunnerStartOrderSentinelTest {

    private val source by lazy {
        TrackedTree.file("libpebble3/src/androidMain/kotlin/io/rebble/libpebblecommon/js/WebViewJsRunner.kt").readText()
    }

    private val startBody = Regex("""override suspend fun start\(\) \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
    private val gate = Regex("""(?m)^ {8}if \(!shouldRunPkjs\(""")
    private val gateReturn = Regex("""(?m)^ {12}return\s*$""")
    private val proxyOverride = Regex("""(?m)^ {8}applyNetworkProxy\(networkAllowed\)\s*$""")
    private val appLoad = Regex("""(?m)^ {8}loadApp\(""")

    private val noOverride = "start() no longer applies the proxy override for the grant it read, as a statement of its own"
    private val overrideAboveGate = "start() applies the proxy override above the gate that can refuse the session"
    private val loadBeforeOverride = "start() loads the app before the proxy override is applied"

    /** The message of every check that [text] fails. */
    private fun problems(text: String): List<String> {
        val body = startBody.find(text)?.groupValues?.get(1)
            ?.lines()?.joinToString("\n") { it.substringBefore("//") }
            ?: return listOf("WebViewJsRunner no longer overrides start()")
        val refusal = gate.find(body)?.let { gateReturn.find(body, it.range.last) }
            ?: return listOf("start() no longer returns from a shouldRunPkjs gate")
        val override = proxyOverride.find(body) ?: return listOf(noOverride)
        val load = appLoad.find(body) ?: return listOf("start() no longer loads the app")
        return buildList {
            if (override.range.first < refusal.range.first) add(overrideAboveGate)
            if (load.range.first < override.range.first) add(loadBeforeOverride)
        }
    }

    @Test
    fun startAppliesTheProxyOverrideBetweenTheGateAndTheLoad() {
        assertEquals(emptyList(), problems(source))
    }

    private fun String.edited(old: String, new: String): String {
        assertTrue(contains(old), "the runner no longer holds the text this edit replaces: $old")
        return replaceFirst(old, new)
    }

    @Test
    fun knownBadEditsAreNoticed() {
        val override = "        applyNetworkProxy(networkAllowed)\n"
        val gateHead = "        if (!shouldRunPkjs("
        val load = "        loadApp(jsPath.toString())\n"
        val edits: Map<String, Pair<String, (String) -> String>> = mapOf(
            "the override is applied above the gate again" to
                (overrideAboveGate to { it.edited(override, "").edited(gateHead, override + gateHead) }),
            "the app loads before the override is applied" to
                (loadBeforeOverride to { it.edited(load, "").edited(override, load + override) }),
            "the override is launched and not awaited" to
                (noOverride to { it.edited(override, "        scope.launch { applyNetworkProxy(networkAllowed) }\n") }),
        )
        for ((name, case) in edits) {
            val (expected, edit) = case
            assertEquals(listOf(expected), problems(edit(source)), name)
        }
    }
}
