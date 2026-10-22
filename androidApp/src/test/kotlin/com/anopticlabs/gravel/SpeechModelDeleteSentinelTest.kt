package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model list's delete goes through deleteModelKeepingMode, and the model manager tells the speech service about
 * each delete. Checked as text, with `//` comment lines left out.
 */
class SpeechModelDeleteSentinelTest {

    private fun code(path: String) =
        TrackedTree.file(path).readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun theModelListDeletesThroughTheModeKeepingDelete() {
        val screen = code("pebble/src/commonMain/kotlin/coredevices/pebble/ui/ModelManagementScreen.kt")
        assertTrue(
            Regex(
                """fun deleteModel\(slug: String\) \{\s*val message = deleteModelKeepingMode\(\s*slug = slug,\s*holder = coreConfigHolder,\s*installed = modelManager::getDownloadedSTTModelSlugs,\s*delete = modelManager::deleteModel,\s*recommended = \{ modelManager\.getRecommendedSTTModel\(\)\.modelSlug \},\s*\)\s*refreshDownloadedModels\(\)\s*message\?\.let\(_snackbarMessages::tryEmit\)\s*\}""",
            ).containsMatchIn(screen),
            "the view model no longer deletes through deleteModelKeepingMode, refreshes its list and shows the message",
        )
        assertFalse("mode = CactusSTTMode.RemoteOnly" in screen, "the model list sets Server Only again")
    }

    @Test
    fun theManagerTellsTheSpeechServiceAfterEachDelete() {
        val manager = code("util/src/commonMain/kotlin/coredevices/util/models/ModelManager.kt")
        assertTrue(
            Regex("""modelPathProvider\?\.deleteModel\(modelName\)\n\s*onDeleted\(modelName\)\n""").containsMatchIn(manager),
            "ModelManager.deleteModel no longer reports the delete after the provider's delete",
        )
        val module = code("composeApp/src/commonMain/kotlin/coredevices/coreapp/di/utilModule.kt")
        assertTrue(
            Regex("""ModelManager\([^{}]*onDeleted = \{\s*get<WhisperTranscriptionService>\(\)\.modelDeleted\(it\)\s*\}\s*\)""")
                .containsMatchIn(module),
            "utilModule no longer hands ModelManager the speech service's modelDeleted",
        )
    }
}
