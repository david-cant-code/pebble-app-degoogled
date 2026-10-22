package com.anopticlabs.gravel.notebook.ui

import androidx.compose.ui.text.buildAnnotatedString
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteRenderLimitsTest {

    private fun tree(markdown: CharSequence, token: CancellationToken = CancellationToken.NonCancellable) =
        MarkdownParser(GFMFlavourDescriptor(), cancellationToken = token).buildMarkdownTreeFromString(markdown)

    private fun all(node: ASTNode): Sequence<ASTNode> = sequenceOf(node) + node.children.asSequence().flatMap { all(it) }

    private fun asWritten(markdown: String, pick: (ASTNode) -> Boolean): String {
        val root = tree(markdown)
        return buildAnnotatedString {
            for (node in all(root).filter(pick)) assertTrue(NoteRenderLimits.appendAsWritten(this, markdown, node), node.type.name)
        }.text
    }

    @Test
    fun ordinaryNotesFit() {
        assertTrue(NoteRenderLimits.fits(tree("# Plan\n\nThe **budget** list:\n\n- one\n  - nested\n\n> quoted\n")))
        assertTrue(NoteRenderLimits.fits(tree("short paragraph\n\n".repeat(5_000))))
        assertTrue(NoteRenderLimits.fits(tree("- item\n".repeat(NoteRenderLimits.MAX_BLOCK_ITEMS))))
    }

    @Test
    fun deepNestingAHugeBlockOrTooManyItemsDoNotFit() {
        assertTrue(NoteRenderLimits.fits(tree(">".repeat(NoteRenderLimits.MAX_DEPTH - 2) + " deep")))
        assertFalse(NoteRenderLimits.fits(tree(">".repeat(NoteRenderLimits.MAX_DEPTH + 1) + " deep")))
        assertFalse(NoteRenderLimits.fits(tree("word ".repeat(NoteRenderLimits.MAX_BLOCK_CHARS / 5 + 10))))
        assertFalse(NoteRenderLimits.fits(tree("- item\n".repeat(NoteRenderLimits.MAX_BLOCK_ITEMS + 1))))
        assertFalse(NoteRenderLimits.fits(tree("| a |\n|---|\n" + "| 1 |\n".repeat(NoteRenderLimits.MAX_BLOCK_ITEMS + 1))))
    }

    @Test
    fun aTreeWithAnEmptyOrRepeatedTopLevelStartDoesNotFit() {
        for (body in listOf("- <!-- todo\n\n \nmore", "1. <!--\n \n]", "1. <v>\n\t\r\n")) {
            val root = tree(body)
            val starts = root.children.map { it.startOffset }
            assertTrue(starts.size != starts.toSet().size || root.children.any { it.endOffset <= it.startOffset }, body)
            assertFalse(NoteRenderLimits.fits(root), body)
        }
    }

    @Test
    fun theDepthGateRefusesDeepContainersBeforeParsing() {
        assertTrue(NoteRenderLimits.mayParse("# Plan\n\n- one\n  - two\n    - three\n\n> quote\n    code\n1. first\n"))
        assertFalse(NoteRenderLimits.mayParse(">".repeat(20_000)))
        assertFalse(NoteRenderLimits.mayParse("ok\n" + "> ".repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"))
        assertFalse(NoteRenderLimits.mayParse("- ".repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"))
        assertFalse(NoteRenderLimits.mayParse("1. ".repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"))
        assertFalse(NoteRenderLimits.mayParse(" ".repeat(2 * NoteRenderLimits.MAX_DEPTH + 2) + "x"))
        assertTrue(NoteRenderLimits.mayParse("> ".repeat(NoteRenderLimits.MAX_DEPTH) + "x"))
    }

    @Test
    fun theDepthGateCountsAListMarkerFollowedByATab() {
        val nested = "-\t".repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"
        assertEquals(NoteRenderLimits.MAX_DEPTH + 1, all(tree(nested)).count { it.type == MarkdownElementTypes.LIST_ITEM })
        for (marker in listOf("-\t", "*\t", "+\t", "1.\t", "1)\t")) {
            assertFalse(NoteRenderLimits.mayParse(marker.repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"), marker)
            assertTrue(NoteRenderLimits.mayParse(marker.repeat(NoteRenderLimits.MAX_DEPTH) + "x"), marker)
        }
    }

    @Test
    fun theDepthGateCountsAListMarkerWithATaskListBoxOnce() {
        for (marker in listOf("- [ ] ", "- [x] ", "* [X] ", "1. [ ] ", "-\t[ ]\t", "-[ ] ", "*[X]\t", "1.[x] ", "1)[ ] ")) {
            val nested = marker.repeat(NoteRenderLimits.MAX_DEPTH + 1) + "x"
            assertEquals(NoteRenderLimits.MAX_DEPTH + 1, all(tree(nested)).count { it.type == MarkdownElementTypes.LIST_ITEM }, marker)
            assertFalse(NoteRenderLimits.mayParse(nested), marker)
            assertTrue(NoteRenderLimits.mayParse(marker.repeat(NoteRenderLimits.MAX_DEPTH) + "x"), marker)
        }
    }

    @Test
    fun theBracketGateRefusesARunOfManyLinkOpenersBeforeParsing() {
        assertFalse(NoteRenderLimits.mayParse("![".repeat(NoteRenderLimits.MAX_RUN_BRACKETS + 1)))
        assertFalse(NoteRenderLimits.mayParse("[a](\n".repeat(NoteRenderLimits.MAX_RUN_BRACKETS + 1)))
        assertTrue(NoteRenderLimits.mayParse("[a](\n\n".repeat(NoteRenderLimits.MAX_RUN_BRACKETS + 1)))
        assertTrue(NoteRenderLimits.mayParse("see [the docs](https://example.org) ".repeat(NoteRenderLimits.MAX_RUN_BRACKETS)))
    }

    @Test
    fun indentationAfterABlockQuoteCountsTowardTheDepth() {
        val items = NoteRenderLimits.MAX_DEPTH - 1
        val first = "> " + "- ".repeat(items) + "x"
        val second = "> " + " ".repeat(2 * items) + "- ".repeat(items) + "x"
        assertEquals(2 * items, deepestItem(tree("$first\n$second")))
        assertTrue(NoteRenderLimits.mayParse(first))
        assertFalse(NoteRenderLimits.mayParse("$first\n$second"))
    }

    private fun deepestItem(node: ASTNode): Int =
        (node.children.maxOfOrNull { deepestItem(it) } ?: 0) + if (node.type == MarkdownElementTypes.LIST_ITEM) 1 else 0

    @Test
    fun onlyALineOfSpacesAndTabsEndsABracketRun() {
        val paragraphs = { separator: String -> all(tree("a\n$separator\nb")).count { it.type == MarkdownElementTypes.PARAGRAPH } }
        assertEquals(2, paragraphs(" \t"))
        val openers = "![".repeat(NoteRenderLimits.MAX_RUN_BRACKETS / 2 + 1)
        for (separator in listOf("\u00A0", "\r", "\u000B", "\u000C", "\u3000")) {
            assertEquals(1, paragraphs(separator), "U+%04X".format(separator[0].code))
            assertFalse(NoteRenderLimits.mayParse("$openers\n$separator\n$openers"), "U+%04X".format(separator[0].code))
        }
        assertTrue(NoteRenderLimits.mayParse("$openers\n \t\n$openers"))
    }

    @Test
    fun theParseBudgetStopsAParseOnceItRunsOut() {
        var checks = 0
        assertFailsWith<NoteRenderLimits.ParseStopped> { tree("[a](".repeat(8_192), NoteRenderLimits.parseBudget { ++checks < 1_000 }) }
        checks = 0
        assertFailsWith<NoteRenderLimits.ParseStopped> { tree("![x\n\n".repeat(8_192), NoteRenderLimits.parseBudget { ++checks < 1_000 }) }
        assertTrue(NoteRenderLimits.fits(tree("a [link](https://example.org)", NoteRenderLimits.parseBudget { true })))
    }

    @Test
    fun equalsSignsHtmlHighlightsImagesAndAngleLinksAreAppendedAsWritten() {
        assertEquals("==", asWritten("x = 5 and 2+2=4") { it.type == GFMTokenTypes.EQUALS })
        assertEquals("<b></b>", asWritten("a <b>x</b> z") { it.type == MarkdownTokenTypes.HTML_TAG })
        assertEquals("==y==", asWritten("pay ==y== now") { it.type == GFMElementTypes.HIGHLIGHT })
        assertEquals("![pic](https://example.org/p.png)", asWritten("see ![pic](https://example.org/p.png)") { it.type == MarkdownElementTypes.IMAGE })
        assertEquals("[x](<https://a.com/b>)", asWritten("go [x](<https://a.com/b>) now") { it.type == MarkdownElementTypes.INLINE_LINK })
        val word = all(tree("plain words")).first { it.type == MarkdownTokenTypes.TEXT }
        assertEquals("", buildAnnotatedString { assertFalse(NoteRenderLimits.appendAsWritten(this, "plain words", word)) }.text)
    }

    @Test
    fun anHtmlBlockInAQuoteShowsItsOwnLines() {
        val text = "> <div>\n> hi\n> </div>"
        val block = all(tree(text)).first { it.type == MarkdownElementTypes.HTML_BLOCK }
        assertFalse("> " in NoteRenderLimits.blockText(block, text).substringAfter("<div>"))
    }

    /**
     * The leaf tokens renderer 0.45.0 draws in running text (AnnotatedStringKtx,
     * buildMarkdownAnnotatedString); anything else the parser emits there must be appended as written.
     */
    private val DRAWN: Set<IElementType> = setOf(
        MarkdownTokenTypes.TEXT, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE, MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN, MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET, MarkdownTokenTypes.LT, MarkdownTokenTypes.GT, MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.EXCLAMATION_MARK, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.HARD_LINE_BREAK,
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.EMAIL_AUTOLINK, GFMTokenTypes.GFM_AUTOLINK, GFMTokenTypes.DOLLAR,
        GFMTokenTypes.TILDE,
    )

    /** Nodes whose inside the renderer or Gravel handles whole: link parts, autolinks, math, and what is appended as written. */
    private fun handledWhole(node: ASTNode) = node.type in setOf(
        MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.LINK_TITLE, MarkdownElementTypes.LINK_LABEL,
        MarkdownElementTypes.AUTOLINK, GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH, GFMElementTypes.HIGHLIGHT,
        MarkdownElementTypes.IMAGE,
    ) || node.type == MarkdownElementTypes.INLINE_LINK && node.children.any { it.type == MarkdownElementTypes.AUTOLINK }

    private fun leavesInRunningText(node: ASTNode, out: MutableSet<String>) {
        if (handledWhole(node)) return
        if (node.children.isEmpty()) {
            if (node.type !in DRAWN && node.type != GFMTokenTypes.EQUALS && node.type != MarkdownTokenTypes.HTML_TAG) out += node.type.name
            return
        }
        node.children.forEach { leavesInRunningText(it, out) }
    }

    @Test
    fun everyTokenInTheRunningTextOfSampleNotesIsDrawnOrAppendedAsWritten() {
        val samples = listOf(
            "x = 5 and 2+2=4, a==b, key=value&k2=v2, x => y",
            "Call **Sam** about the _budget_ and ~~old~~ plan, `git status`, \\*escaped\\*",
            "See [the docs](https://example.org \"title\") or <https://example.org/x> or www.example.org or someone@example.org",
            "Costs \$5 and \$10, 3 < 4 & 5 > 2, 'quoted' \"double\" (paren) [bracket] a: b! c",
            "Pay ==rent== and <i>tax</i>, ![pic](p.png), [ref][r] and [short]",
            "line one  \nline two\\\nline three",
            "Math \$x^2\$ and \$\$y\$\$ inline, &amp; &copy; entities, # not a heading",
            "- item with = sign\n- [ ] task\n- [x] done\n\n1. first=1\n\n> quote with = and `a=b`",
            "# Heading = one\n\nSetext = two\n===\n\n| a=1 | b |\n|---|---|\n| c=2 | d |",
            "[^1]: a footnote-shaped line\n[label]: https://example.org",
        )
        val unknown = HashSet<String>()
        for (sample in samples) {
            val root = tree(sample)
            all(root).filter { it.type in setOf(MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT, GFMTokenTypes.CELL) }
                .forEach { leavesInRunningText(it, unknown) }
        }
        assertEquals(emptySet(), unknown)
    }
}
