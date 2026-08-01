package coredevices.coreapp.model

import coredevices.util.CommonBuildKonfig
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the pin table itself: every model the build can request must have
 * well-formed integrity data, the marker semantics must reinstall exactly
 * when the pin moves (grandfathering the pre-pinning tag markers until
 * then), the table must have been derived from the weights tag the build
 * ships (so an upstream tag bump cannot be silently inert), and the
 * bundled LM asset at the path the APK packages must match its pin, so a
 * silently swapped or drifted asset fails the suite (the runtime digest
 * gate would refuse it only at install time, on a user's device).
 */
class CactusModelPinsTest {

    private val stt = CommonBuildKonfig.CACTUS_STT_MODEL
    private val lm = CommonBuildKonfig.CACTUS_LM_MODEL_NAME

    @Test
    fun everyBuildKonfigModelHasAPin() {
        assertNotNull(CactusModelPins.pinFor(stt), "STT model '$stt' must be pinned")
        assertNotNull(CactusModelPins.pinFor(lm), "LM model '$lm' must be pinned")
    }

    @Test
    fun pinsWereDerivedFromTheCurrentWeightsTag() {
        // An upstream CACTUS_WEIGHTS_VERSION bump merges conflict-free and
        // changes nothing at runtime here (downloads resolve the pinned
        // commits, not the tag), so without this tripwire users would just
        // silently keep the old weights forever.
        assertEquals(
            CommonBuildKonfig.CACTUS_WEIGHTS_VERSION,
            CactusModelPins.DERIVED_FROM_WEIGHTS_TAG,
            "the weights tag moved; run the re-pin procedure in the CactusModelPins KDoc",
        )
    }

    @Test
    fun pinsAreWellFormed() {
        listOf(stt, lm).forEach { name ->
            val pin = assertNotNull(CactusModelPins.pinFor(name))
            assertTrue(pin.commitSha.matches(Regex("[0-9a-f]{40}")), "$name commitSha malformed: ${pin.commitSha}")
            assertTrue(pin.zipSha256Hex.matches(Regex("[0-9a-f]{64}")), "$name sha256 malformed: ${pin.zipSha256Hex}")
            assertTrue(pin.zipSizeBytes > 0, "$name size must be positive")
        }
    }

    @Test
    fun unknownModelHasNoPinAndNeverMatches() {
        assertNull(CactusModelPins.pinFor("whisper-tiny"))
        assertFalse(CactusModelPins.markerMatches("whisper-tiny", "anything"))
    }

    @Test
    fun pinnedDigestMarkerMatches() {
        listOf(stt, lm).forEach { name ->
            val pin = assertNotNull(CactusModelPins.pinFor(name))
            assertTrue(CactusModelPins.markerMatches(name, pin.zipSha256Hex))
        }
    }

    @Test
    fun legacyTagMarkersAreGrandfatheredWhileThePinsStillNameTheV201Archives() {
        // Both current pins are the v2.0.1 archives, so installs whose
        // marker predates digest pinning must not be reinstalled (383 MB
        // re-download) or flagged incompatible (RemoteOnly downgrade).
        listOf(stt, lm).forEach { name ->
            assertTrue(
                CactusModelPins.markerMatches(name, CactusModelPins.LEGACY_TAG_MARKER),
                "legacy marker must stay valid for '$name' while its pin is unchanged",
            )
        }
    }

    @Test
    fun aMovedPinRejectsTheLegacyMarkerAndTheOldDigest() {
        val oldSha = "aa".repeat(32)
        val newSha = "bb".repeat(32)
        val moved = ModelPin(
            hfRepo = "some-model",
            commitSha = "cc".repeat(20),
            zipSha256Hex = newSha,
            zipSizeBytes = 1,
        )
        // Once a pin names a new archive, everything older must reinstall:
        // the legacy tag, and markers of the previously pinned digest.
        assertFalse(CactusModelPins.markerMatchesPin(CactusModelPins.LEGACY_TAG_MARKER, moved, legacySha256 = oldSha))
        assertFalse(CactusModelPins.markerMatchesPin(oldSha, moved, legacySha256 = oldSha))
        assertTrue(CactusModelPins.markerMatchesPin(newSha, moved, legacySha256 = oldSha))
        // A model with no legacy history never accepts the tag marker.
        assertFalse(CactusModelPins.markerMatchesPin(CactusModelPins.LEGACY_TAG_MARKER, moved, legacySha256 = null))
        // The grandfather clause: legacy marker accepted only while the pin
        // still names the archive the tag shipped.
        val unmoved = moved.copy(zipSha256Hex = oldSha)
        assertTrue(CactusModelPins.markerMatchesPin(CactusModelPins.LEGACY_TAG_MARKER, unmoved, legacySha256 = oldSha))
    }

    @Test
    fun bundledLmAssetMatchesItsPin() {
        val pin = assertNotNull(CactusModelPins.pinFor(lm))
        // Hash the path the APK actually packages (currently a symlink into
        // the repo-root models/, resolved on read), not its target: this way
        // a retargeted, replaced, or broken symlink goes red too, not just
        // an edit to the target file. The walk up from the test working dir
        // keeps the check independent of where Gradle runs it.
        val assetPath = "composeApp/src/androidMain/assets/models/${ModelZipInstaller.zipNameFor(lm)}"
        val asset = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { it.resolve(assetPath) }
            .firstOrNull { it.isFile }
            ?: fail("could not locate $assetPath from ${System.getProperty("user.dir")}")
        assertEquals(pin.zipSizeBytes, asset.length(), "bundled LM asset size drifted from its pin")
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(pin.zipSha256Hex, sha, "bundled LM asset digest drifted from its pin")
    }
}
