package coredevices.util

const val FORK_REPOSITORY_URL = "https://github.com/david-cant-code/pebble-app-degoogled"

/**
 * Where problems with this unofficial build are reported. One constant so
 * the screens that point people at the tracker cannot drift apart; issues
 * seen on this fork belong here, not with watchface developers or
 * upstream support.
 */
const val FORK_ISSUE_TRACKER_URL = "$FORK_REPOSITORY_URL/issues"

/** KNOWN_ISSUES.md headings the app links to; KnownIssuesLinksTest checks each is still a heading there. */
object KnownIssue {
    const val REAL_TIME_CONNECTIONS_ON_ONE_LAYER =
        "Where the UDP filter has never installed, WebRTC depends on one layer"
    const val UDP_FILTER_REFUSED = "If Android refuses the UDP filter"
}

/**
 * KNOWN_ISSUES.md at the release tag [appVersion], or at master for any other build, opened at the
 * entry titled [heading]. A release's versionName is its tag.
 */
fun knownIssuesUrl(appVersion: String, heading: String): String {
    val ref = if (RELEASE_VERSION.matches(appVersion)) appVersion else "master"
    return "$FORK_REPOSITORY_URL/blob/$ref/KNOWN_ISSUES.md#${githubHeadingAnchor(heading)}"
}

private val RELEASE_VERSION = Regex("""\d+(\.\d+)+""")

// GitHub Docs, "Basic writing and formatting syntax", Section links: letters lower-cased, spaces
// replaced by hyphens, other whitespace and punctuation removed. Written for the ASCII letters,
// digits and punctuation that KnownIssuesLinksTest allows in a linked heading.
fun githubHeadingAnchor(heading: String): String =
    heading.trim().lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(' ', '-')
