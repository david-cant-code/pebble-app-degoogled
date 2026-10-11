package com.anopticlabs.gravel.app

import coredevices.coreapp.ui.sttConfigAfterUpdateDownload
import coredevices.coreapp.ui.sttUpdatePromptApplies
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the engine-upgrade dialog applies, and what its finished download writes to the speech settings. */
class SttModelUpdatePromptTest {

    /** The mode the startup sweep leaves beside each stashed mode: Server Only for one that uses the phone. */
    private val leftBySweep = mapOf(
        CactusSTTMode.RemoteFirst to CactusSTTMode.RemoteOnly,
        CactusSTTMode.LocalOnly to CactusSTTMode.RemoteOnly,
        CactusSTTMode.LocalFirst to CactusSTTMode.RemoteOnly,
        CactusSTTMode.RebbleFirst to CactusSTTMode.RemoteOnly,
        CactusSTTMode.RebbleFallback to CactusSTTMode.RemoteOnly,
        CactusSTTMode.RemoteOnly to CactusSTTMode.RemoteOnly,
        CactusSTTMode.RebbleOnly to CactusSTTMode.RebbleOnly,
        CactusSTTMode.PlatformOnly to CactusSTTMode.PlatformOnly,
    )

    @Test
    fun theDialogAppliesOnlyWhileTheModeIsTheOneTheSweepLeft() {
        assertEquals(CactusSTTMode.entries.toSet(), leftBySweep.keys)
        for (mode in CactusSTTMode.entries) {
            assertEquals(false, sttUpdatePromptApplies(stashed = null, mode), "no stash, $mode")
            for ((stashed, left) in leftBySweep) {
                assertEquals(mode == left, sttUpdatePromptApplies(stashed, mode), "$stashed stashed, $mode")
            }
        }
    }

    @Test
    fun theDownloadRestoresTheStashedModeOverServerOnlyAndKeepsAnyOtherMode() {
        for (stashed in listOf(CactusSTTMode.LocalFirst, CactusSTTMode.LocalOnly, CactusSTTMode.RebbleOnly)) {
            for (mode in CactusSTTMode.entries) {
                val before = STTConfig(mode, modelName = null, spokenLanguage = "de", serverUrl = "https://a.example.net/inference")
                assertEquals(
                    before.copy(mode = if (mode == CactusSTTMode.RemoteOnly) stashed else mode, modelName = "whisper-base-en"),
                    sttConfigAfterUpdateDownload(before, stashed, slug = "whisper-base-en"),
                    "$stashed stashed, $mode",
                )
            }
        }
    }
}
