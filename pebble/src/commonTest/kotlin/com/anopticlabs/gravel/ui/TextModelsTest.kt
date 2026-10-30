package com.anopticlabs.gravel.ui

import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.LlmSpeedCalibration
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.transcription.SpeedScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextModelsTest {
    private val large = LlmModelCatalog.MODELS.maxBy { it.sizeBytes }
    private val small = LlmModelCatalog.MODELS.minBy { it.sizeBytes }
    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    private fun rows(facts: TextModelFacts, recommendedId: String? = null, score: SpeedScore? = null, ram: Long = 8 * gib) =
        textModelRows(facts, recommendedId, score, ram).associateBy { it.model.id }

    @Test
    fun eachRowShowsItsModelsState() {
        val rows = rows(
            TextModelFacts(
                installed = setOf(small.id),
                partialBytes = mapOf(large.id to 100 * mib, small.id to 5 * mib),
                inUse = small.id,
            ),
        )
        assertEquals(TextModelState.Partial(100 * mib), rows.getValue(large.id).state)
        assertEquals(TextModelState.Installed, rows.getValue(small.id).state)
        assertTrue(rows.getValue(small.id).inUse)
        assertFalse(rows.getValue(large.id).inUse)
    }

    @Test
    fun onlyAnInstalledModelIsMarkedInUse() {
        assertFalse(rows(TextModelFacts(inUse = large.id, partialBytes = mapOf(large.id to 9 * mib))).getValue(large.id).inUse)
    }

    @Test
    fun aRunningDownloadHoldsEveryOtherDownloadBack() {
        val rows = rows(TextModelFacts(download = ModelDownloadStatus.Downloading(large.id)))
        assertEquals(TextModelState.Downloading, rows.getValue(large.id).state)
        assertFalse(rows.getValue(large.id).canDownload)
        assertFalse(rows.getValue(small.id).canDownload)
    }

    @Test
    fun aPhoneThatCannotRunTheEngineGetsNoDownloadButKeepsItsModels() {
        val rows = rows(TextModelFacts(engineSupported = false, installed = setOf(small.id), partialBytes = mapOf(large.id to 9 * mib)))
        assertFalse(rows.getValue(large.id).canDownload)
        assertEquals(TextModelState.Installed, rows.getValue(small.id).state)
        assertEquals(TextModelState.Partial(9 * mib), rows.getValue(large.id).state)
    }

    @Test
    fun aPartlyDownloadedModelCanBeContinued() {
        assertTrue(rows(TextModelFacts(partialBytes = mapOf(large.id to 9 * mib))).getValue(large.id).canDownload)
    }

    @Test
    fun aPartUnderHalfAMegabyteReadsAsLessThanOne() {
        val facts = TextModelFacts(partialBytes = mapOf(large.id to 1000L))
        val row = rows(facts).getValue(large.id)
        assertEquals(TextModelState.Partial(1000L), row.state)
        assertEquals("Paused at less than 1 of 1056 MB", partialDownloadText(large, 1000L))
        assertTrue(textModelDeleteMessage(row, facts, recommended = null).contains("This frees less than 1 MB,"))
    }

    @Test
    fun aModelOverTheNoteBudgetIsMarkedSlow() {
        val score = SpeedScore(LlmSpeedCalibration.SPEECH_REFERENCE_SCORE_NS * 10, threads = 2, measuredAtEpochMs = 0L)
        val rows = rows(TextModelFacts(), score = score)
        assertTrue(rows.getValue(large.id).slow)
        assertFalse(rows(TextModelFacts()).getValue(large.id).slow, "no score, no estimate")
    }

    @Test
    fun onAPhoneThatCannotRunTheEngineADeletePromisesNothingElse() {
        val facts = TextModelFacts(engineSupported = false, installed = setOf(small.id), inUse = small.id)
        assertEquals(
            "Delete ${small.displayName}? This frees about 563 MB.",
            textModelDeleteMessage(rows(facts).getValue(small.id), facts, recommended = null),
        )
    }

    @Test
    fun aFailedDownloadIsShownOnItsOwnRowOnly() {
        val rows = rows(TextModelFacts(download = ModelDownloadStatus.Failed(large.id, "Not enough free storage")))
        assertEquals("Not enough free storage", rows.getValue(large.id).failure)
        assertNull(rows.getValue(small.id).failure)
        assertTrue(rows.getValue(large.id).canDownload)
    }

    @Test
    fun aLoadFailureIsShownOnTheModelInUse() {
        val rows = rows(TextModelFacts(installed = setOf(small.id, large.id), inUse = large.id, inUseLoadFailed = true))
        assertTrue(rows.getValue(large.id).loadFailed)
        assertFalse(rows.getValue(small.id).loadFailed)
    }

    @Test
    fun theRecommendationAndTheMemoryWarningAreMarked() {
        val rows = rows(TextModelFacts(), recommendedId = small.id, ram = 2 * gib)
        assertTrue(rows.getValue(small.id).recommended)
        assertFalse(rows.getValue(large.id).recommended)
        assertTrue(rows.getValue(large.id).tooHeavy)
        assertFalse(rows(TextModelFacts(), ram = 0L).getValue(large.id).tooHeavy, "an unknown total never warns")
    }

    @Test
    fun theDetailGivesTheSizeAndTheEstimate() {
        assertEquals("563 MB", textModelDetail(small, null))
        assertEquals("1056 MB, about 18 s per one-minute recording, after transcription", textModelDetail(large, 17.9))
        assertEquals(
            "1056 MB, about 60 s per one-minute recording, after transcription, slow on this phone",
            textModelDetail(large, LlmSpeedCalibration.NOTE_BUDGET_SECONDS + 15),
        )
    }

    @Test
    fun aPartialDownloadSaysHowFarItGot() {
        assertEquals("Paused at 900 of 1056 MB", partialDownloadText(large, 900L * 1024 * 1024))
    }

    @Test
    fun deletingTheModelInUseNamesWhatTheNotebookUsesNext() {
        val facts = TextModelFacts(installed = setOf(small.id, large.id), chosen = large.id, inUse = large.id)
        val row = rows(facts).getValue(large.id)
        assertEquals(
            "Delete ${large.displayName}? This frees about 1056 MB. The Notebook will use ${small.displayName} instead.",
            textModelDeleteMessage(row, facts, recommended = large),
        )
    }

    @Test
    fun theNextModelNamedFollowsTheNotebooksPick() {
        val medium = large.copy(id = "medium", displayName = "Medium", sizeBytes = large.sizeBytes / 2)
        val models = listOf(large, medium, small)
        val facts = TextModelFacts(installed = models.map { it.id }.toSet(), chosen = small.id, inUse = small.id)
        val row = textModelRows(facts, null, null, 8 * gib, models).first { it.model == small }
        assertTrue(textModelDeleteMessage(row, facts, recommended = medium, models = models).endsWith("will use Medium instead."))
        assertTrue(textModelDeleteMessage(row, facts, recommended = null, models = models).endsWith("will use ${large.displayName} instead."))
        val otherChosen = facts.copy(chosen = medium.id, inUse = large.id)
        val largeRow = textModelRows(otherChosen, null, null, 8 * gib, models).first { it.model == large }
        assertTrue(textModelDeleteMessage(largeRow, otherChosen, recommended = small, models = models).endsWith("will use Medium instead."))
    }

    @Test
    fun deletingTheOnlyModelSaysRecordingsWillWait() {
        val facts = TextModelFacts(installed = setOf(small.id), inUse = small.id)
        assertEquals(
            "Delete ${small.displayName}? This frees about 563 MB. Notebook recordings will wait until you download a text model.",
            textModelDeleteMessage(rows(facts).getValue(small.id), facts, recommended = null),
        )
    }

    @Test
    fun deletingAModelNotInUseChangesNothingElse() {
        val facts = TextModelFacts(installed = setOf(small.id, large.id), inUse = large.id)
        assertEquals(
            "Delete ${small.displayName}? This frees about 563 MB, and you'd have to download it again to use it.",
            textModelDeleteMessage(rows(facts).getValue(small.id), facts, recommended = null),
        )
    }

    @Test
    fun deletingAPartialDownloadSaysTheNextStartsOver() {
        val facts = TextModelFacts(partialBytes = mapOf(large.id to 900L * 1024 * 1024))
        assertEquals(
            "Delete the downloaded part of ${large.displayName}? This frees about 900 MB, and a new download starts from the beginning.",
            textModelDeleteMessage(rows(facts).getValue(large.id), facts, recommended = null),
        )
    }

    @Test
    fun theSettingsLineFollowsTheModels() {
        assertEquals("No text model installed", textModelsSummary(TextModelFacts()))
        assertEquals(small.displayName, textModelsSummary(TextModelFacts(installed = setOf(small.id), inUse = small.id)))
        assertEquals(
            "Downloading ${large.displayName}",
            textModelsSummary(TextModelFacts(inUse = small.id, download = ModelDownloadStatus.Downloading(large.id))),
        )
        assertEquals("This phone can't run a text model", textModelsSummary(TextModelFacts(engineSupported = false, inUse = small.id)))
        assertEquals(
            "${small.displayName} could not be loaded",
            textModelsSummary(TextModelFacts(installed = setOf(small.id), inUse = small.id, inUseLoadFailed = true)),
        )
    }

    @Test
    fun memoryIsShownInTenthsSoAPhoneUnderTheFloorNeverReadsAsMeetingIt() {
        assertEquals("3", gigabytes(3 * gib))
        assertEquals("2.6", gigabytes(26 * gib / 10))
        assertTrue(heavyWarning(large, 26 * gib / 10).contains("at least 3 GB of memory and this one has 2.6 GB"))
        val justFlagged = large.minRamBytes * 10 / 11 - 1
        assertTrue(rows(TextModelFacts(), ram = justFlagged).getValue(large.id).tooHeavy)
        assertTrue(heavyWarning(large, justFlagged).contains("this one has 2.7 GB"))
    }
}
