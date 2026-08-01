package coredevices.pebble.firmware

import coredevices.util.CoreConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Firmware version parsed from a PebbleOS release tag or a watch-reported
 * version string, keeping up to four numeric components.
 *
 * Fork-owned on purpose, separate from libpebble3's FirmwareVersion: that
 * parser matches an un-anchored prefix, so a four-component factory tag like
 * "v4.9.142.3" silently loses its fourth component, and its comparison falls
 * through to a timestamp tiebreak when the truncated components are equal.
 * Release selection needs to (a) recognize factory-line tags structurally so
 * they are never offered, and (b) compare the running firmware against a
 * candidate such that an equal version is never re-offered (a timestamp
 * tiebreak would re-offer the installed build forever, because a release's
 * publish time is always later than the firmware's build time).
 */
data class ReleaseTagVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val fourth: Int,
    val componentCount: Int,
    val raw: String,
) : Comparable<ReleaseTagVersion> {
    /**
     * The manufacturing line uses four-component tags (v4.9.142.x today).
     * Its post-branch commits are factory-test work only, so those builds
     * must never be offered as an update, however new their publish date.
     */
    val isFactoryLine: Boolean get() = componentCount >= 4

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

/**
 * Which tier of PebbleOS releases to offer. Background (verified 2026-07-31):
 * Core's CI deploys every tag only to the internal Memfault cohort; the
 * public rollout is a manually promoted main-line release that lags the
 * newest tag by roughly 2 to 7 days, and some tags are never promoted.
 */
enum class FirmwareUpdateChannel {
    /**
     * Approximate the public rollout: newest main-line minor whose first
     * release has soaked at least [SOAK_WINDOW], but the highest patch within
     * that minor immediately (a fresh hotfix stabilizes the promoted build,
     * so delaying it would keep users on the build it fixes).
     */
    Soaked,

    /** Newest main-line tag: the tier Core's internal testers run. */
    Early,
}

/**
 * The persisted setting is a plain boolean (a two-state toggle in watch
 * settings); the mapping lives here so the DI wiring and tests share one
 * definition of which state means which channel.
 */
fun CoreConfig.firmwareUpdateChannel(): FirmwareUpdateChannel =
    if (firmwareUpdatesEarlyChannel) FirmwareUpdateChannel.Early else FirmwareUpdateChannel.Soaked

/** One GitHub release reduced to what selection needs. */
data class SelectableRelease(
    val version: ReleaseTagVersion,
    val publishedAt: Instant,
    /** Whether this release publishes a normal-firmware asset for the target hardware. */
    val hasAsset: Boolean,
)

/**
 * Picks the release to offer for one watch, or null when nothing qualifies
 * (callers surface that as a failed check, never as a silent fallback).
 *
 * Never trust GitHub's "latest" badge or publish dates for ordering: the
 * release workflow sets no make_latest, so the badge just tracks the most
 * recently published tag of either line, and a factory tag would capture it.
 * Selection is structural instead: factory-line tags are dropped, and
 * main-line candidates are ordered by parsed version.
 *
 * Hardware ramp: a new board revision only gains assets from some release
 * onward, so when the policy's pick predates the hardware, walk forward to
 * the nearest newer release that has the asset rather than offering nothing.
 */
fun selectRelease(
    releases: List<SelectableRelease>,
    channel: FirmwareUpdateChannel,
    now: Instant,
    soak: Duration = SOAK_WINDOW,
): SelectableRelease? {
    val mainLine = releases.filter { !it.version.isFactoryLine }
    return when (channel) {
        FirmwareUpdateChannel.Early -> mainLine.filter { it.hasAsset }.maxByOrNull { it.version }
        FirmwareUpdateChannel.Soaked -> {
            val byMinor = mainLine.groupBy { it.version.major to it.version.minor }
            val soakedMinors = byMinor.filterValues { minor ->
                minor.minOf { it.publishedAt } + soak <= now
            }
            val target = soakedMinors.values.flatten().maxByOrNull { it.version } ?: return null
            if (target.hasAsset) {
                target
            } else {
                mainLine
                    .filter { it.hasAsset && it.version > target.version }
                    .minByOrNull { it.version }
            }
        }
    }
}

/**
 * How long a minor must have been public before the Soaked channel offers it.
 * Chosen from the observed public-changelog promotion lag of 2 to 7 days;
 * a documented constant so it can be tuned deliberately, not incidentally.
 */
val SOAK_WINDOW: Duration = 7.days
