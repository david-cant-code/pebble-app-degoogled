package com.anopticlabs.gravel.models

import coredevices.util.transcription.SpeedScore
import coredevices.util.transcription.WhisperSpeedCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextModelRecommendationTest {

    private val gib = 1024L * 1024L * 1024L
    private val eightGiB = 8 * gib
    private val large = LlmModelCatalog.MODELS.first()
    private val small = LlmModelCatalog.MODELS.last()

    /** A score [ratio] times slower than the reference phone's. */
    private fun score(ratio: Double) =
        SpeedScore((LlmSpeedCalibration.SPEECH_REFERENCE_SCORE_NS * ratio).toLong(), threads = 2, measuredAtEpochMs = 0L)

    /** The score ratio at which [model]'s estimate equals the budget. */
    private fun budgetRatio(model: LlmModel): Double =
        LlmSpeedCalibration.NOTE_BUDGET_SECONDS /
            (LlmSpeedCalibration.referenceNoteSeconds(model.id)!! * WhisperSpeedCalibration.BACKGROUND_MARGIN)

    @Test
    fun everyTextModelIsCalibrated() {
        for (model in LlmModelCatalog.MODELS) {
            assertNotNull(LlmSpeedCalibration.referenceNoteSeconds(model.id), "${model.id} has no reference time")
        }
    }

    @Test
    fun textTimesAreScaledToTheCurrentSpeechReference() {
        assertEquals(WhisperSpeedCalibration.REFERENCE_SCORE_NS, LlmSpeedCalibration.SPEECH_REFERENCE_SCORE_NS)
    }

    @Test
    fun catalogStepsDownFromTheLargestModel() {
        val sizes = LlmModelCatalog.MODELS.map { it.sizeBytes }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun estimateScalesTheReferenceByTheScoreWithTheBackgroundMargin() {
        val reference = LlmSpeedCalibration.referenceNoteSeconds(large.id)!!
        val margin = WhisperSpeedCalibration.BACKGROUND_MARGIN
        assertEquals(reference * margin, LlmSpeedCalibration.estimateNoteSeconds(large.id, score(1.0))!!, 1e-6)
        assertEquals(reference * 3 * margin, LlmSpeedCalibration.estimateNoteSeconds(large.id, score(3.0))!!, 1e-6)
    }

    @Test
    fun noEstimateWithoutAScoreOrForAnIdOutsideTheTextCatalog() {
        assertNull(LlmSpeedCalibration.estimateNoteSeconds(large.id, null))
        assertNull(LlmSpeedCalibration.estimateNoteSeconds("whisper-base-en", score(1.0)))
        assertNull(LlmSpeedCalibration.estimateNoteSeconds("unknown", score(1.0)))
    }

    @Test
    fun theReferencePhoneGetsTheLargestModel() {
        assertEquals(large, recommendedTextModel(engineSupported = true, totalRamBytes = eightGiB, score = score(1.0)))
    }

    @Test
    fun withoutAScoreTheLargestModelStands() {
        assertEquals(large, recommendedTextModel(engineSupported = true, totalRamBytes = eightGiB, score = null))
    }

    @Test
    fun aPhoneOverTheBudgetStepsDownAtTheBudget() {
        val atBudget = budgetRatio(large)
        assertEquals(large, recommendedTextModel(true, eightGiB, score(atBudget * 0.999)))
        assertEquals(small, recommendedTextModel(true, eightGiB, score(atBudget * 1.001)))
    }

    @Test
    fun theSmallestModelIsTheFloorHoweverSlow() {
        val beyondEveryBudget = LlmModelCatalog.MODELS.maxOf(::budgetRatio) * 4
        assertEquals(small, recommendedTextModel(true, eightGiB, score(beyondEveryBudget)))
    }

    @Test
    fun aModelOverItsRamFloorStepsDownToOneWithinItsOwn() {
        val heavy = large.copy(id = "heavy", minRamBytes = 8 * gib)
        val light = small.copy(id = "light", minRamBytes = 3 * gib)
        val catalog = listOf(heavy, light)
        assertEquals(light, recommendedTextModel(true, totalRamBytes = 6 * gib, score = null, models = catalog))
        assertEquals(heavy, recommendedTextModel(true, totalRamBytes = 8 * gib, score = null, models = catalog))
    }

    @Test
    fun ramUnderEveryFloorStillOffersTheSmallestModel() {
        assertEquals(small, recommendedTextModel(true, totalRamBytes = 1 * gib, score = null))
    }

    @Test
    fun noRecommendationWhereTheEngineCannotRun() {
        assertNull(recommendedTextModel(engineSupported = false, totalRamBytes = eightGiB, score = score(1.0)))
    }

    @Test
    fun ramFloorAllowsTenPercentSlack() {
        assertFalse(exceedsDeviceRam(minRamBytes = 3 * gib, totalRamBytes = 3 * gib))
        assertFalse(exceedsDeviceRam(minRamBytes = 11 * gib, totalRamBytes = 10 * gib))
        assertTrue(exceedsDeviceRam(minRamBytes = 11 * gib + 1, totalRamBytes = 10 * gib))
        assertTrue(exceedsDeviceRam(minRamBytes = 8 * gib, totalRamBytes = 6 * gib))
    }

    @Test
    fun anUnknownTotalIsNeverExceeded() {
        assertFalse(exceedsDeviceRam(minRamBytes = 12 * gib, totalRamBytes = 0L))
        assertFalse(exceedsDeviceRam(minRamBytes = 12 * gib, totalRamBytes = -1L))
    }
}
