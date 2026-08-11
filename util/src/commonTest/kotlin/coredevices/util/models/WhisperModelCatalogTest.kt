package coredevices.util.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the model catalog: the integrity values verbatim (an accidental
 * edit to any pin must fail the suite, not ship), the URL shape that
 * makes downloads immutable, and the recommendation tiering.
 */
class WhisperModelCatalogTest {

    @Test
    fun pinnedHashesAndSizesAreExactlyAsVetted() {
        // Derived 2026-08-09 via the catalog's three-source procedure;
        // restated verbatim so any drift in the table is a loud failure
        // naming this test, which points back at that procedure.
        val vetted = mapOf(
            "whisper-small" to
                ("1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b" to 487_601_967L),
            "whisper-small-en" to
                ("c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d" to 487_614_201L),
            "whisper-base" to
                ("60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe" to 147_951_465L),
            "whisper-base-en" to
                ("a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002" to 147_964_211L),
        )
        assertEquals(vetted.keys, WhisperModelCatalog.ids)
        for (model in WhisperModelCatalog.MODELS) {
            val (sha256, sizeBytes) = vetted.getValue(model.id)
            assertEquals(sha256, model.sha256, "sha256 drifted for ${model.id}")
            assertEquals(sizeBytes, model.sizeBytes, "size drifted for ${model.id}")
        }
    }

    @Test
    fun pinsAreWellFormed() {
        for (model in WhisperModelCatalog.MODELS) {
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")), "${model.id} sha256 malformed")
            assertTrue(model.sizeBytes > 0, "${model.id} size must be positive")
            assertTrue(model.minRamBytes > 0, "${model.id} minRam must be positive")
            assertTrue(model.fileName.startsWith("ggml-") && model.fileName.endsWith(".bin"))
        }
        assertTrue(WhisperModelCatalog.HF_REPO_COMMIT.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun idsAndFileNamesAreUnique() {
        val models = WhisperModelCatalog.MODELS
        assertEquals(models.size, models.map { it.id }.toSet().size)
        assertEquals(models.size, models.map { it.fileName }.toSet().size)
    }

    @Test
    fun urlsResolveThePinnedCommitOnly() {
        for (model in WhisperModelCatalog.MODELS) {
            assertEquals(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
                    "5359861c739e955e79d9a303bcbc70fb988958b1/${model.fileName}",
                WhisperModelCatalog.urlFor(model),
            )
        }
    }

    @Test
    fun englishOnlyVariantsAreMarkedNotMultilingual() {
        for (model in WhisperModelCatalog.MODELS) {
            assertEquals(
                !model.id.endsWith("-en"),
                model.multilingual,
                "multilingual flag disagrees with the id convention for ${model.id}",
            )
        }
    }

    @Test
    fun unknownIdResolvesToNothing() {
        assertNull(WhisperModelCatalog.byId("parakeet-tdt-0.6b-v3"))
        assertNull(WhisperModelCatalog.byId("needle-pebble-ft"))
    }

    @Test
    fun recommendationTiersByRamAndLanguage() {
        val gib = 1024L * 1024 * 1024
        assertEquals("whisper-small-en", WhisperModelCatalog.recommended(8 * gib, preferEnglishOnly = true).model.id)
        assertEquals("whisper-small", WhisperModelCatalog.recommended(8 * gib, preferEnglishOnly = false).model.id)
        assertEquals("whisper-base-en", WhisperModelCatalog.recommended(3 * gib, preferEnglishOnly = true).model.id)
        assertEquals("whisper-base", WhisperModelCatalog.recommended(3 * gib, preferEnglishOnly = false).model.id)
    }

    @Test
    fun recommendationTierFlagMatchesTheChosenModel() {
        // The tier flag and the model come from one decision, so the
        // Lite/Standard label a caller derives can never contradict the id.
        val gib = 1024L * 1024 * 1024
        assertTrue(WhisperModelCatalog.recommended(8 * gib, preferEnglishOnly = false).standardTier)
        assertTrue(WhisperModelCatalog.recommended(8 * gib, preferEnglishOnly = true).standardTier)
        assertFalse(WhisperModelCatalog.recommended(3 * gib, preferEnglishOnly = false).standardTier)
        assertFalse(WhisperModelCatalog.recommended(3 * gib, preferEnglishOnly = true).standardTier)
        // Exactly at the threshold is the Standard tier.
        assertTrue(
            WhisperModelCatalog.recommended(
                WhisperModelCatalog.STANDARD_TIER_MIN_TOTAL_RAM, preferEnglishOnly = false,
            ).standardTier,
        )
    }

    @Test
    fun generationCannotCollideWithLegacyCactusMarkers() {
        // The migration notification dedupes on the stored last-notified
        // value, which on upgraded devices is the Cactus weights tag; a
        // collision would silently suppress the one-time migration
        // notification for every existing user.
        assertTrue(WhisperModelCatalog.GENERATION.isNotBlank())
        assertNotEquals("v2.0.1", WhisperModelCatalog.GENERATION)
    }
}
