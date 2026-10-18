package com.anopticlabs.gravel.notebook.ui

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken

/**
 * What the note page renders as markdown and how, on the parser's types alone. Kept apart from
 * the renderer's types, which ship as Java 21 class files a JDK 17 host test cannot load.
 */
object NoteRenderLimits {
    /** Nested deeper, a body is shown plain: the renderer composes one level per nested block (renderer 0.45.0, MarkdownElement). */
    const val MAX_DEPTH = 32

    /** With a longer top-level block, a body is shown plain: the lazy list lays out each block whole (renderer 0.45.0, LazyMarkdownSuccess). */
    const val MAX_BLOCK_CHARS = 16 * 1024

    /** With more list items or table rows in one top-level block, a body is shown plain, for the same reason. */
    const val MAX_BLOCK_ITEMS = 500

    /**
     * How long a parse may run before the body is shown plain. The parser consults its token at
     * each position of the block pass and before each inline pass, not inside one (parser
     * 0.7.15, collectBlockProduction and SequentialParserManager.runParsingSequence), which is
     * why [MAX_RUN_BRACKETS] exists.
     */
    const val PARSE_BUDGET_MILLIS = 2_000L

    /** More `[` than this in one run of lines without a blank one, and a body is not parsed: link and image openers make the parser's inline pass superlinear. */
    const val MAX_RUN_BRACKETS = 1024

    private val ITEMS = setOf(MarkdownElementTypes.LIST_ITEM, GFMElementTypes.ROW)

    /**
     * Whether [body] may be parsed at all, judged on its text before any parse: no line starts
     * with more than [MAX_DEPTH] levels as counted here (a block quote marker, a list marker, and
     * indentation at two columns a level past one space or tab after each marker; a task-list box
     * adds no level), and no run of lines without a blank one (spaces and tabs only) holds more
     * than [MAX_RUN_BRACKETS] `[`.
     */
    fun mayParse(body: CharSequence): Boolean {
        var start = 0
        var brackets = 0
        while (start <= body.length) {
            val end = body.indexOf('\n', start).let { if (it < 0) body.length else it }
            if (containerDepth(body, start, end) > MAX_DEPTH) return false
            if ((start until end).all { body[it].isSpaceOrTab() }) {
                brackets = 0
            } else {
                for (i in start until end) if (body[i] == '[') brackets++
                if (brackets > MAX_RUN_BRACKETS) return false
            }
            start = end + 1
        }
        return true
    }

    private fun containerDepth(text: CharSequence, start: Int, end: Int): Int {
        var depth = 0
        var columns = 0
        var i = start
        while (i < end) {
            val c = text[i]
            // Indentation after a marker counts too: it continues the list items of the lines above.
            when {
                c == ' ' -> columns++
                c == '\t' -> columns += 4
                c == '>' -> {
                    depth++
                    if (i + 1 < end && text[i + 1].isSpaceOrTab()) i++
                }
                (c == '-' || c == '*' || c == '+') && endsListMarker(text, i + 1, end) -> {
                    depth++
                    if (text[i + 1].isSpaceOrTab()) i++
                }
                c.isDigit() -> {
                    var j = i
                    while (j < end && text[j].isDigit()) j++
                    if (j < end && (text[j] == '.' || text[j] == ')') && endsListMarker(text, j + 1, end)) {
                        depth++
                        i = if (text[j + 1].isSpaceOrTab()) j + 1 else j
                    } else {
                        break
                    }
                }
                isTaskBox(text, i, end) -> {
                    i += 2
                    if (i + 1 < end && text[i + 1].isSpaceOrTab()) i++
                }
                else -> break
            }
            if (depth + columns / 2 > MAX_DEPTH) return depth + columns / 2
            i++
        }
        return depth + columns / 2
    }

    /**
     * Whether what is at [i] after a list marker character lets the gate count it: a space or tab, or a task-list box,
     * which the parser reads as part of the marker with or without spaces or tabs before it (parser 0.7.15,
     * GFMConstraints.fetchListMarker).
     */
    private fun endsListMarker(text: CharSequence, i: Int, end: Int) = i < end && (text[i].isSpaceOrTab() || isTaskBox(text, i, end))

    private fun isTaskBox(text: CharSequence, i: Int, end: Int) =
        i + 2 < end && text[i] == '[' && text[i + 2] == ']' && (text[i + 1] == ' ' || text[i + 1] == 'x' || text[i + 1] == 'X')

    /** The whitespace the parser takes after a list marker and on a blank line (parser 0.7.15, CommonMarkdownConstraints.tryAddListItem, LookaheadText.Position.charsToNonWhitespace). */
    private fun Char.isSpaceOrTab() = this == ' ' || this == '\t'

    /** A token that stops a parse, with [ParseStopped], once [keepGoing] turns false. */
    fun parseBudget(keepGoing: () -> Boolean) = CancellationToken { if (!keepGoing()) throw ParseStopped() }

    class ParseStopped : RuntimeException("markdown parse stopped")

    /**
     * Whether the tree under [root] can be laid out: root children with distinct start offsets
     * and none empty (the lazy list keys its items by start offset, renderer 0.45.0,
     * LazyMarkdownSuccess), within [MAX_DEPTH], [MAX_BLOCK_CHARS] and [MAX_BLOCK_ITEMS].
     */
    fun fits(root: ASTNode): Boolean {
        val starts = HashSet<Int>()
        for (block in root.children) {
            if (block.endOffset <= block.startOffset || !starts.add(block.startOffset)) return false
            if (block.endOffset - block.startOffset > MAX_BLOCK_CHARS) return false
        }
        val pending = ArrayDeque<Triple<ASTNode, Int, Int>>()
        val items = IntArray(root.children.size)
        root.children.forEachIndexed { index, block -> pending.addLast(Triple(block, 1, index)) }
        while (pending.isNotEmpty()) {
            val (node, level, block) = pending.removeLast()
            if (level > MAX_DEPTH) return false
            if (node.type in ITEMS && ++items[block] > MAX_BLOCK_ITEMS) return false
            for (child in node.children) pending.addLast(Triple(child, level + 1, block))
        }
        return true
    }

    private val AS_WRITTEN: Set<IElementType> =
        setOf(MarkdownTokenTypes.HTML_TAG, GFMElementTypes.HIGHLIGHT, GFMTokenTypes.EQUALS, MarkdownElementTypes.IMAGE)

    /**
     * Appends, as the text it is written as, an inline HTML tag, a `==highlight==`, an `=` (the
     * renderer draws none of these), an image (a note's images are not loaded) and a link whose
     * destination is in angle brackets (the renderer shows only its text); false for any other node.
     */
    fun appendAsWritten(builder: AnnotatedString.Builder, content: String, child: ASTNode): Boolean {
        val asWritten = child.type in AS_WRITTEN ||
            child.type == MarkdownElementTypes.INLINE_LINK && child.children.any { it.type == MarkdownElementTypes.AUTOLINK }
        if (!asWritten) return false
        builder.append(child.getTextInNode(content).toString())
        return true
    }

    /** The text a block the note page shows as written displays: an HTML block's own lines, without the markers of the containers around it. */
    fun blockText(node: ASTNode, content: String): String {
        val lines = node.children.filter { it.type == MarkdownTokenTypes.HTML_BLOCK_CONTENT }
        return if (lines.isEmpty()) node.getTextInNode(content).toString() else lines.joinToString("\n") { it.getTextInNode(content) }
    }
}
