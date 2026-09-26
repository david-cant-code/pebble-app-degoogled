package com.anopticlabs.gravel.util

import coredevices.util.FORK_REPOSITORY_URL
import coredevices.util.KnownIssue
import coredevices.util.githubHeadingAnchor
import coredevices.util.knownIssuesUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class ForkLinksTest {
    @Test
    fun aReleaseLinksToItsTagAndAnyOtherBuildToMaster() {
        val anchor = "#if-android-refuses-the-udp-filter"
        for (version in listOf("0.3.2", "1.0.0", "0.3")) {
            assertEquals(
                "$FORK_REPOSITORY_URL/blob/$version/KNOWN_ISSUES.md$anchor",
                knownIssuesUrl(version, KnownIssue.UDP_FILTER_REFUSED),
            )
        }
        for (version in listOf("0.3.2-2-g4c0fc202", "unknown", "", "0.3.2-rc1")) {
            assertEquals(
                "$FORK_REPOSITORY_URL/blob/master/KNOWN_ISSUES.md$anchor",
                knownIssuesUrl(version, KnownIssue.UDP_FILTER_REFUSED),
                version,
            )
        }
    }

    // The documented example, without the markup and the non-ASCII letter the linked headings may not hold.
    @Test
    fun anchorsFollowGitHubsSectionLinkRules() {
        assertEquals("sample-section", githubHeadingAnchor("Sample Section"))
        assertEquals("thisll-be-a-helpful-section-about-the-greek-letter", githubHeadingAnchor("This'll be a Helpful Section About the Greek Letter!"))
        assertEquals(
            "where-the-udp-filter-has-never-installed-webrtc-depends-on-one-layer",
            githubHeadingAnchor(KnownIssue.REAL_TIME_CONNECTIONS_ON_ONE_LAYER),
        )
    }
}
