package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `MainApplication.onCreate` holds the Notebook's start call and the registration its visibility gates count
 * activities through. The file is upstream's and no host test runs it. Checked as text: the statements at the top
 * level of `onCreate`, by how each line starts.
 */
class NotebookStartSentinelTest {

    private val statements by lazy {
        val source = TrackedTree.file("composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt").readText()
        val body = assertNotNull(
            Regex("""override fun onCreate\(\) \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1),
            "MainApplication no longer overrides onCreate",
        )
        // Eight spaces is the top level of onCreate's body; a statement nested in a block has more.
        body.lines().filter { it.length > 8 && it.startsWith(" ".repeat(8)) && it[8] != ' ' }.map { it.trim() }
    }

    private fun position(statement: String): Int {
        val at = statements.withIndex().filter { it.value.startsWith(statement) }.map { it.index }
        assertEquals(1, at.size, "top-level statements of MainApplication.onCreate that start with $statement")
        return at.single()
    }

    /** The first run may send to the self-hosted server, so the Wi-Fi rule and the token's adoption come first. */
    @Test
    fun onCreateStartsTheNotebookAfterTheServerRules() {
        val start = position("get<NotebookStartup>().start()")
        for (earlier in listOf("get<AndroidUnmeteredWifi>().start()", "settleServerOnMobileData(", "adoptUnboundServerToken(")) {
            assertTrue(position(earlier) < start, "MainApplication.onCreate starts the Notebook before $earlier")
        }
    }

    @Test
    fun onCreateRegistersTheNotebooksActivityCallbacks() {
        position("registerActivityLifecycleCallbacks(get<AppVisibility>())")
    }
}
