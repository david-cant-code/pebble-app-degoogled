package coredevices.coreapp.model

import coredevices.util.CommonBuildKonfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the provider's install decision against real marker files in temp
 * dirs: the file-reading glue production runs (marker filename, trim,
 * existence checks) on top of the markerMatchesPin semantics that
 * CactusModelPinsTest covers in the pure form, plus the fail-closed
 * refusal for unpinned model names. A regression in any of these silently
 * forces a 383 MB re-download for every existing user, downgrades them to
 * RemoteOnly STT via the incompatible-model sweep, or reopens the
 * unverified tag-download path the pin gate closed.
 */
class CactusModelProviderDecisionTest {

    private val modelsDir = Files.createTempDirectory("model-decision-test").toFile()
    private val stt = CommonBuildKonfig.CACTUS_STT_MODEL
    private val sttPin = assertNotNull(CactusModelPins.pinFor(stt))

    private fun seed(marker: String? = null, config: Boolean = true) {
        val dir = modelsDir.resolve(stt).also { it.mkdirs() }
        if (config) dir.resolve(ModelZipInstaller.CONFIG_FILE).writeText("cfg")
        marker?.let { dir.resolve(ModelZipInstaller.VERSION_MARKER).writeText(it) }
    }

    @Test
    fun missingModelDirNeedsInstall() {
        assertTrue(CactusModelProvider.needsInstallIn(modelsDir, stt))
        assertFalse(CactusModelProvider.versionMatchesIn(modelsDir, stt))
    }

    @Test
    fun configWithoutMarkerNeedsInstall() {
        seed(marker = null)
        assertTrue(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun markerWithoutConfigNeedsInstall() {
        seed(marker = sttPin.zipSha256Hex, config = false)
        assertTrue(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun verifiedDigestMarkerSkipsInstall() {
        seed(marker = sttPin.zipSha256Hex)
        assertFalse(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun markerIsTrimmedBeforeComparison() {
        // The pre-pinning installer wrote the marker without a newline, but
        // the read must stay tolerant of one; a dropped trim would force a
        // reinstall on every launch for affected installs.
        seed(marker = "${sttPin.zipSha256Hex}\n")
        assertFalse(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun legacyTagMarkerIsGrandfatheredThroughTheFileGlue() {
        seed(marker = CactusModelPins.LEGACY_TAG_MARKER)
        assertFalse(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun staleMarkerForcesReinstall() {
        seed(marker = "aa".repeat(32))
        assertTrue(CactusModelProvider.needsInstallIn(modelsDir, stt))
    }

    @Test
    fun unpinnedModelNeverVersionMatches() {
        val dir = modelsDir.resolve("whisper-tiny").also { it.mkdirs() }
        dir.resolve(ModelZipInstaller.CONFIG_FILE).writeText("cfg")
        dir.resolve(ModelZipInstaller.VERSION_MARKER).writeText("aa".repeat(32))
        assertFalse(CactusModelProvider.versionMatchesIn(modelsDir, "whisper-tiny"))
        assertTrue(CactusModelProvider.needsInstallIn(modelsDir, "whisper-tiny"))
    }

    @Test
    fun requirePinFailsClosedForUnpinnedModels() {
        // The old code shape (fall back to a tag-based download) still
        // exists in util's ModelManager, so the refusal must stay pinned
        // against a quiet reintroduction.
        val e = assertFailsWith<IllegalStateException> {
            CactusModelProvider.requirePin("whisper-tiny")
        }
        assertTrue(e.message.orEmpty().contains("No integrity pin"), "unexpected message: ${e.message}")
    }

    @Test
    fun requirePinReturnsThePinForPinnedModels() {
        assertEquals(sttPin, CactusModelProvider.requirePin(stt))
    }
}
