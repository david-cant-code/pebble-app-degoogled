package coredevices.coreapp.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of upstream's ModelNeedsReplacementTest (experimental/src/commonTest/
// kotlin/coredevices/ring/model/ModelNeedsReplacementTest.kt), which stopped
// running anywhere when :experimental left the build. The predicate it pins
// now ships in the fork-owned CactusModelProvider copy, where it decides
// when downloaded STT/LM model weights are deleted and the STT mode is
// downgraded, so a silent regression would break on-device dictation.
class ModelNeedsReplacementTest {

    private val stt = "parakeet-tdt-0.6b-v3"
    private val lm = "needle-pebble-ft"
    private val compatible = setOf(stt, lm)

    @Test
    fun staleNetworkModel_isReplaced() {
        assertTrue(
            CactusModelProvider.modelNeedsReplacement(
                stt, compatible, versionMatches = false, bundledInApp = false
            )
        )
    }

    @Test
    fun staleBundledModel_isKept() {
        assertFalse(
            CactusModelProvider.modelNeedsReplacement(
                lm, compatible, versionMatches = false, bundledInApp = true
            )
        )
    }

    @Test
    fun currentVersion_isKept() {
        assertFalse(
            CactusModelProvider.modelNeedsReplacement(
                stt, compatible, versionMatches = true, bundledInApp = false
            )
        )
        assertFalse(
            CactusModelProvider.modelNeedsReplacement(
                lm, compatible, versionMatches = true, bundledInApp = true
            )
        )
    }

    @Test
    fun unknownName_isAlwaysReplaced() {
        assertTrue(
            CactusModelProvider.modelNeedsReplacement(
                "whisper-tiny", compatible, versionMatches = true, bundledInApp = false
            )
        )
        assertTrue(
            CactusModelProvider.modelNeedsReplacement(
                "whisper-tiny", compatible, versionMatches = true, bundledInApp = true
            )
        )
    }
}
