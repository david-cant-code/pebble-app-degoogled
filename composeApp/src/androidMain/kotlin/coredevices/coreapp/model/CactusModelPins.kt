package coredevices.coreapp.model

/**
 * Everything the installer must know to verify one model archive before it
 * is allowed anywhere near the native parser.
 *
 * @param hfRepo repository name under the Cactus-Compute Hugging Face org.
 * @param commitSha immutable revision the download URL resolves; unlike the
 *   upstream tag-based URLs this cannot be retargeted after the fact.
 * @param zipSha256Hex lowercase hex SHA-256 of the exact archive bytes.
 * @param zipSizeBytes exact archive size; enforced mid-download so a
 *   substituted response is cut off instead of filling the disk.
 */
data class ModelPin(
    val hfRepo: String,
    val commitSha: String,
    val zipSha256Hex: String,
    val zipSizeBytes: Long,
)

/**
 * Hardcoded integrity pins for the on-device Cactus model archives. The
 * download URL is pinned to an immutable commit and the received bytes are
 * verified against a build-time SHA-256 and exact size, so neither a
 * retargeted tag nor a compromised repository or CDN can swap the weights
 * that feed the native libcactus_engine.so parser; the bundled asset goes
 * through the same digest gate.
 *
 * Re-pin procedure when bumping to a new release (each step is an
 * independent source; all three must agree before the new values land):
 *  1. Tag to commit: `https://huggingface.co/api/models/Cactus-Compute/
 *     <repo>/refs` and read the target commit of the release tag.
 *  2. Declared metadata: HEAD `https://huggingface.co/Cactus-Compute/
 *     <repo>/resolve/<commit>/<zip>` and read `x-linked-size` and
 *     `x-linked-etag` (the etag is the payload's SHA-256).
 *  3. Independent bytes: download that URL fresh and run `sha256sum` and
 *     a size check locally; this is the value pair that goes in the table,
 *     with 1 and 2 as cross-checks.
 * When the STT pin changes, also bump CACTUS_WEIGHTS_VERSION: the reinstall
 * itself is driven by the pin (the on-disk marker stops matching), but the
 * system notification in CommonAppDelegate dedupes on the tag string, so
 * without a bump users get the in-app SttModelUpdatePrompt and no
 * notification.
 */
object CactusModelPins {
    /**
     * Marker value written by installs that predate digest pinning, when
     * .cactus_version carried the CACTUS_WEIGHTS_VERSION tag. Grandfathered
     * per model for as long as the model's pin still points at the archive
     * that tag shipped ([legacyV201Sha256]): those installs hold bytes
     * identical to today's pinned archive, so re-downloading hundreds of MB
     * (or downgrading users to remote-only STT while it happens) would buy
     * nothing. The first real pin bump breaks the equality and forces a
     * verified reinstall.
     */
    const val LEGACY_TAG_MARKER = "v2.0.1"

    // Values derived 2026-08-01 via the three-source procedure above; both
    // archives are the v2.0.1 release of the Cactus cq4 quantization.
    private val PINS = mapOf(
        "parakeet-tdt-0.6b-v3" to ModelPin(
            hfRepo = "parakeet-tdt-0.6b-v3",
            commitSha = "26fa0fdba867416e6df970c517ac95c9bdce7c4b",
            zipSha256Hex = "f3225606761c1c38d8ac9a057d6d95cc5ff63cb4236a9ee63880a9f56ab57e1b",
            zipSizeBytes = 383_664_950,
        ),
        "needle-pebble-ft" to ModelPin(
            hfRepo = "needle-pebble-ft",
            commitSha = "fbfe3b5ac54b50cba17b700313f98b3311829eb9",
            zipSha256Hex = "7e08fa6a6ef38f79429bc95b1ae31f326b53a67fcb8768df19f875fa67ec852e",
            zipSizeBytes = 13_732_623,
        ),
    )

    /**
     * SHA-256 of the archive each model shipped under the "v2.0.1" tag
     * markers; the historical anchor for [LEGACY_TAG_MARKER] acceptance.
     * These never change once recorded, unlike the live pins above.
     */
    private val LEGACY_V201_SHAS = mapOf(
        "parakeet-tdt-0.6b-v3" to "f3225606761c1c38d8ac9a057d6d95cc5ff63cb4236a9ee63880a9f56ab57e1b",
        "needle-pebble-ft" to "7e08fa6a6ef38f79429bc95b1ae31f326b53a67fcb8768df19f875fa67ec852e",
    )

    fun pinFor(modelName: String): ModelPin? = PINS[modelName]

    private fun legacyV201Sha256(modelName: String): String? = LEGACY_V201_SHAS[modelName]

    /**
     * Whether an on-disk .cactus_version marker proves the installed model
     * matches the current pin: either it is the pin's own digest (written
     * by every verified install) or the grandfathered legacy tag while the
     * pin still names the same archive that tag shipped.
     */
    fun markerMatches(modelName: String, marker: String): Boolean {
        val pin = pinFor(modelName) ?: return false
        return markerMatchesPin(marker, pin, legacyV201Sha256(modelName))
    }

    // Pure so the grandfather semantics are testable against fabricated
    // pins, not just today's table.
    internal fun markerMatchesPin(marker: String, pin: ModelPin, legacySha256: String?): Boolean =
        marker == pin.zipSha256Hex ||
            (marker == LEGACY_TAG_MARKER && legacySha256 != null && pin.zipSha256Hex == legacySha256)
}
