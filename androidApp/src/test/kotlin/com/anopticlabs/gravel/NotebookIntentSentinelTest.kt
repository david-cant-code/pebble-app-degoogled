package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MainActivity is upstream-owned: a Notebook notification tap must reach NotebookIntents before
 * any other handler and end the handling there, so its data URI never reaches the link handlers.
 * NotebookIntentsTest covers the decision itself. Checked as text, comment lines left out.
 */
class NotebookIntentSentinelTest {

    @Test
    fun handleIntentGivesNotebookTapsToNotebookIntentsFirst() {
        val activity = TrackedTree.file("composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt").readLines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
        val body = activity.substringAfter("private fun handleIntent(intent: Intent) {")
        assertTrue(
            Regex("""^\s*if \(notebookIntents\.take\([\s\S]*?\)\s*\{\s*setIntent\(null\)\s*return\s*\}""").containsMatchIn(body),
            "handleIntent no longer starts by handing Notebook taps to NotebookIntents and returning",
        )
    }
}
