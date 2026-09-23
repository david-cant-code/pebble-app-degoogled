package com.anopticlabs.gravel.models

import coredevices.util.models.WhisperModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun textModelPinsAreExactlyAsVetted() {
        val vetted = mapOf(
            "qwen3-1_7b-q4_k_m" to listOf(
                "unsloth/Qwen3-1.7B-GGUF",
                "d7f544eead698dbd1f15126ef60b45a1e1933222",
                "Qwen3-1.7B-Q4_K_M.gguf",
                "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
                "1107409472",
            ),
            "qwen3_5-0_8b-q5_k_m" to listOf(
                "unsloth/Qwen3.5-0.8B-GGUF",
                "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
                "Qwen3.5-0.8B-Q5_K_M.gguf",
                "c3ef5827b322c4be08a3a26ce424460d8b37daf593356c2cc1f4e40a7ab0581b",
                "590057728",
            ),
        )
        assertEquals(vetted.keys, LlmModelCatalog.MODELS.map { it.id }.toSet())
        for (model in LlmModelCatalog.MODELS) {
            assertEquals(
                vetted.getValue(model.id),
                listOf(model.repo, model.commit, model.fileName, model.sha256, model.sizeBytes.toString()),
                "pin drifted for ${model.id}",
            )
        }
    }

    @Test
    fun everyModelIsUnderAnAllowedLicense() {
        for (model in ModelCatalog.MODELS) {
            assertTrue(model.license in ModelCatalog.ALLOWED_LICENSES, "${model.id} is under ${model.license}")
        }
    }

    @Test
    fun theCombinedCatalogIsBothRolesWithUniqueSafeIds() {
        assertEquals(WhisperModelCatalog.MODELS + LlmModelCatalog.MODELS, ModelCatalog.MODELS)
        assertTrue(WhisperModelCatalog.MODELS.all { it.role == ModelRole.Speech })
        assertTrue(LlmModelCatalog.MODELS.all { it.role == ModelRole.Text })
        val ids = ModelCatalog.MODELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids collide across roles")
        for (model in ModelCatalog.MODELS) {
            // The id is a directory name under models/ beside the dot-named staging dir.
            assertTrue(model.id.matches(Regex("[a-z0-9][a-z0-9._-]*")), "${model.id} is not a plain directory name")
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")), "${model.id} sha256 malformed")
            assertTrue(model.commit.matches(Regex("[0-9a-f]{40}")), "${model.id} commit malformed")
            assertTrue(model.sizeBytes > 0 && model.minRamBytes > 0)
        }
    }

    @Test
    fun textModelsAreGgufFilesRenderedAsChatMlWithThinkingSuppressed() {
        for (model in LlmModelCatalog.MODELS) {
            assertTrue(model.fileName.endsWith(".gguf"))
            assertEquals(ChatFormat.ChatMl, model.chatFormat)
            assertEquals("<think>\n\n</think>\n\n", model.promptSuffix)
        }
    }

    @Test
    fun urlsResolveThePinnedCommit() {
        val model = LlmModelCatalog.byId("qwen3_5-0_8b-q5_k_m")!!
        assertEquals(
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/" +
                "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q5_K_M.gguf",
            ModelCatalog.urlFor(model),
        )
    }

    @Test
    fun lookupsCrossRolesOnlyInTheCombinedCatalog() {
        assertEquals(ModelRole.Text, ModelCatalog.byId("qwen3-1_7b-q4_k_m")?.role)
        assertEquals(ModelRole.Speech, ModelCatalog.byId("whisper-base-en")?.role)
        assertNull(WhisperModelCatalog.byId("qwen3-1_7b-q4_k_m"))
        assertNull(LlmModelCatalog.byId("whisper-base-en"))
        assertNull(ModelCatalog.byId("parakeet-tdt-0.6b-v3"))
    }
}
