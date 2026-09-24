package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import coredevices.util.KnownIssue
import coredevices.util.githubHeadingAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app opens KNOWN_ISSUES.md at a heading's anchor (githubHeadingAnchor), and a renamed heading
 * would leave the link landing at the top of the file.
 */
class KnownIssuesLinksTest {
    // Every level: GitHub keeps anchors unique across the whole file.
    private val headings by lazy {
        val heading = Regex("""^#{1,6} (.*)$""")
        TrackedTree.file("KNOWN_ISSUES.md").readLines().mapNotNull { heading.find(it)?.groupValues?.get(1)?.trim() }
    }

    // Every const val in KnownIssue, which compiles to a static String field.
    private val linked = KnownIssue::class.java.fields.filter { it.type == String::class.java }.map { it.get(null) as String }

    @Test
    fun theLinkedHeadingsAreFound() {
        assertTrue(linked.containsAll(listOf(KnownIssue.REAL_TIME_CONNECTIONS_ON_ONE_LAYER, KnownIssue.UDP_FILTER_REFUSED)), "$linked")
    }

    // A second heading with the same anchor gets a suffixed one (GitHub Docs, "Basic writing and
    // formatting syntax", Section links), and the link would reach the first.
    @Test
    fun everyLinkedHeadingIsInTheFileWithAnAnchorOfItsOwn() {
        for (heading in linked) {
            assertTrue(heading in headings, "KNOWN_ISSUES.md has no heading \"$heading\"")
            val anchor = githubHeadingAnchor(heading)
            assertEquals(1, headings.count { anchor in possibleAnchors(it) }, "headings that may have the anchor $anchor")
        }
    }

    @Test
    fun linkedHeadingsHoldOnlyCharactersWhoseAnchorRuleIsDocumented() {
        for (heading in linked) {
            assertTrue(heading.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ',." }, heading)
        }
    }

    // GitHub documents no rule for hyphens and underscores, so a heading holding one counts as
    // colliding under either reading.
    private fun possibleAnchors(heading: String): Set<String> = setOf(
        githubHeadingAnchor(heading),
        heading.trim().lowercase().filter { it.isLetterOrDigit() || it in " -_" }.replace(' ', '-'),
    )
}
