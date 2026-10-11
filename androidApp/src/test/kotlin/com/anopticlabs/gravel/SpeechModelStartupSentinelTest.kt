package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup sweep is handed the model manager's recommendation, and the engine-upgrade dialog decides through the
 * two host-tested functions. Checked as text, with `//` comment lines left out.
 */
class SpeechModelStartupSentinelTest {

    private fun code(path: String) =
        TrackedTree.file(path).readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun theSweepIsHandedTheManagersRecommendation() {
        val delegate = code("composeApp/src/commonMain/kotlin/coredevices/coreapp/CommonAppDelegate.kt")
        assertTrue(
            Regex(
                """runSttModelMigration\(\s*modelProvider,\s*settings,\s*coreConfigHolder,\s*recommended = \{\s*org\.koin\.mp\.KoinPlatform\.getKoin\(\)\.get<ModelManager>\(\)\.getRecommendedSTTModel\(\)\.modelSlug\s*\},\s*\) \{""",
            ).containsMatchIn(delegate),
            "initLocalSttModels no longer hands the sweep the model manager's recommended speech model",
        )
    }

    @Test
    fun theUpgradeDialogDecidesThroughTheTestedFunctions() {
        val prompt = code("composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/SttModelUpdatePrompt.kt")
        assertTrue(
            Regex(
                """fun stashedMode\(\): CactusSTTMode\? =\s*settings\.getIntOrNull\(STT_MODE_BEFORE_UPDATE_KEY\)\?\.let \{ CactusSTTMode\.fromId\(it\) \}""",
            ).containsMatchIn(prompt),
            "the dialog no longer reads the stashed mode from the settings",
        )
        assertTrue(
            Regex(
                """val required = isWhisperSupported\(\) &&\s*sttUpdatePromptApplies\(stashedMode\(\), configHolder\.config\.value\.sttConfig\.mode\) &&\s*withContext\(Dispatchers\.IO\) \{ !anyModelInstalled\(\) \}\n""",
            ).containsMatchIn(prompt),
            "the dialog no longer asks sttUpdatePromptApplies with the stash and the current mode",
        )
        assertTrue(
            Regex(
                """configHolder\.update\(\s*configHolder\.config\.value\.copy\(\s*sttConfig = sttConfigAfterUpdateDownload\(configHolder\.config\.value\.sttConfig, stashed, slug\)\s*\)\s*\)""",
            ).containsMatchIn(prompt),
            "the dialog's finished download no longer writes through sttConfigAfterUpdateDownload",
        )
        assertEquals(1, Regex("""configHolder\.update\(""").findAll(prompt).count(), "the dialog writes the config a second time")
        assertEquals(1, Regex("""\bmode = """).findAll(prompt).count(), "a mode is written outside sttConfigAfterUpdateDownload")
    }
}
