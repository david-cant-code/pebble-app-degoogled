package com.anopticlabs.gravel.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anopticlabs.gravel.models.LlmModelCatalog
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings > AI > LLM composed on the device: the memory question before a download, the delete question, a part
 * kept by an interrupted download, and a phone that cannot run the engine.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.ui.TextModelsContentTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class TextModelsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val large = LlmModelCatalog.MODELS.maxBy { it.sizeBytes }
    private val small = LlmModelCatalog.MODELS.minBy { it.sizeBytes }
    private val gib = 1024L * 1024L * 1024L
    private val downloads = mutableListOf<String>()
    private val deletes = mutableListOf<String>()

    private fun show(facts: TextModelFacts, ramBytes: Long = 8 * gib) {
        val rows = textModelRows(facts, recommendedId = small.id, score = null, totalRamBytes = ramBytes)
        compose.setContent {
            TextModelsContent(
                facts = facts,
                rows = rows,
                recommended = small,
                totalRamBytes = ramBytes,
                score = null,
                measuring = false,
                onRetestSpeed = {},
                onDownload = { downloads += it },
                onCancelDownload = {},
                onChoose = {},
                onDelete = { deletes += it },
            )
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aDownloadOverThePhonesMemoryAsksFirst() {
        show(TextModelFacts(installed = setOf(small.id), inUse = small.id), ramBytes = 2 * gib)
        compose.onAllNodesWithContentDescription("Download")[0].performClick()
        waitFor("May run slowly")
        assertTrue(downloads.isEmpty())
        compose.onNodeWithText("Download anyway").performClick()
        compose.waitForIdle()
        assertEquals(listOf(large.id), downloads)
    }

    @Test
    fun aDownloadWithinThePhonesMemoryStartsAtOnce() {
        show(TextModelFacts(installed = setOf(small.id), inUse = small.id))
        compose.onAllNodesWithContentDescription("Download")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(large.id), downloads)
        assertTrue(compose.onAllNodes(hasText("May run slowly")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deletingTheModelInUseAsksFirstAndNamesWhatComesNext() {
        show(TextModelFacts(installed = setOf(small.id, large.id), inUse = large.id, chosen = large.id))
        compose.onAllNodesWithContentDescription("Delete")[0].performClick()
        waitFor("The Notebook will use ${small.displayName} instead.")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(deletes.isEmpty())
        compose.onAllNodesWithContentDescription("Delete")[0].performClick()
        waitFor("Delete model?")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf(large.id), deletes)
    }

    @Test
    fun aKeptPartCanBeContinuedOrDeleted() {
        show(TextModelFacts(partialBytes = mapOf(large.id to 900L * 1024 * 1024)))
        waitFor("Paused at 900 of 1056 MB")
        compose.onAllNodesWithContentDescription("Delete")[0].performClick()
        waitFor("Delete downloaded part?")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf(large.id), deletes)
        compose.onAllNodesWithContentDescription("Download")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(large.id), downloads)
    }

    @Test
    fun aPhoneThatCannotRunTheEngineGetsNoDownload() {
        show(TextModelFacts(engineSupported = false))
        waitFor("This phone's processor can't run these models")
        compose.onAllNodesWithContentDescription("Download")[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Download")[1].assertIsNotEnabled()
    }
}
