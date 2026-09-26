package coredevices.pebble.firmware

/**
 * Firmware version parsed from a PebbleOS release tag or a watch-reported
 * version string, keeping up to four numeric components.
 *
 * Fork-owned on purpose, separate from libpebble3's FirmwareVersion: that
 * parser matches an un-anchored prefix, so a four-component factory tag like
 * "v4.9.142.3" silently loses its fourth component, and its comparison falls
 * through to a timestamp tiebreak when the truncated components are equal.
 * Update checks compare the running firmware against a candidate with this
 * type so an equal version is never re-offered (a timestamp tiebreak would
 * re-offer the installed build forever, because a release's publish time is
 * always later than the firmware's build time).
 */
data class ReleaseTagVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val fourth: Int,
    val componentCount: Int,
    val raw: String,
) : Comparable<ReleaseTagVersion> {
    /** Suffixes are ignored: main-line release tags carry none, and recovery
     * suffixes on watch-reported versions are handled via isRecovery flags. */
    override fun compareTo(other: ReleaseTagVersion): Int = compareValuesBy(
        this, other,
        { it.major }, { it.minor }, { it.patch }, { it.fourth },
    )

    companion object {
        // Anchored via matchEntire below: a string that does not wholly match
        // (allowing an optional "-suffix") is rejected instead of being
        // prefix-truncated the way libpebble3's regex find() would.
        private val TAG_REGEX = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(.*))?""")

        fun from(tag: String): ReleaseTagVersion? {
            val trimmed = tag.trim()
            val match = TAG_REGEX.matchEntire(trimmed) ?: return null
            val components = (1..4).map { match.groupValues[it].toIntOrNull() }
            return ReleaseTagVersion(
                major = components[0] ?: return null,
                minor = components[1] ?: return null,
                patch = components[2] ?: 0,
                fourth = components[3] ?: 0,
                componentCount = components.count { it != null },
                raw = trimmed,
            )
        }
    }
}
