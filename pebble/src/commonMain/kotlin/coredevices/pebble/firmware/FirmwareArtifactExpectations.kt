package coredevices.pebble.firmware

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a firmware download at a given URL must turn out to be before it may
 * reach the watch. */
data class ExpectedFirmwareArtifact(
    /** Lowercase 64-char hex sha256 of the exact artifact bytes. */
    val sha256Hex: String,
    /** Exact artifact size when the source publishes one (GitHub does; cohorts does not). */
    val sizeBytes: Long?,
    /** Release tag the artifact belongs to, cross-checked against the PBZ manifest. */
    val versionTag: String,
)

/**
 * Check-time integrity metadata, keyed by download URL, for the verified
 * installer to enforce at install time.
 *
 * Exists because upstream's FoundUpdate carries only (version, url, notes):
 * there is no field for a hash to travel in, and widening that type would put
 * a fork diff in libpebble3. Checkers record what their source declares and
 * the installer refuses any download it has no expectation for (fail closed).
 * This state is process-lifetime by design, the same lifetime as the
 * update-check cache and the availableUpdates flows a FoundUpdate lives in,
 * so a lookup miss is a bug or a process restart, never a legitimate state.
 */
class FirmwareArtifactExpectations {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, ExpectedFirmwareArtifact>()

    suspend fun record(url: String, expected: ExpectedFirmwareArtifact) {
        mutex.withLock {
            // Re-insert so eviction order follows recording order; the cap is
            // hygiene only, a few watches need one live entry each.
            entries.remove(url)
            entries[url] = expected
            while (entries.size > MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
        }
    }

    suspend fun lookup(url: String): ExpectedFirmwareArtifact? =
        mutex.withLock { entries[url] }

    companion object {
        private const val MAX_ENTRIES = 16
    }
}

/**
 * Normalizes a hash string to lowercase 64-hex, accepting an optional
 * "sha256:" prefix (GitHub's asset digest format; cohorts publishes bare
 * hex). Returns null for anything else so callers skip recording instead of
 * recording something the installer could never verify against.
 */
fun normalizeSha256Hex(value: String?): String? {
    val hex = value?.trim()?.removePrefix("sha256:")?.lowercase() ?: return null
    if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return hex
}
