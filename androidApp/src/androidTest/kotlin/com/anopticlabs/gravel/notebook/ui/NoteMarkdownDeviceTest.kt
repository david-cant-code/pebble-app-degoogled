package com.anopticlabs.gravel.notebook.ui

import android.content.Intent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Note text through the markdown renderer with the note page's annotator, on the device: the
 * renderer ships Java 21 class files that a JDK 17 host test cannot load.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.notebook.ui.NoteMarkdownDeviceTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class NoteMarkdownDeviceTest {

    private fun paragraphText(markdown: String): String {
        val state = assertIs<State.Success>(parseMarkdown(markdown, lookupLinks = false, flavour = GFMFlavourDescriptor(), referenceLinkHandler = NoteLinks))
        val paragraph = all(state.node).first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), noteAnnotator, NoteLinks, null)
        return buildAnnotatedString { buildMarkdownAnnotatedString(markdown, paragraph.children, settings) }.text
    }

    private fun all(node: ASTNode): Sequence<ASTNode> = sequenceOf(node) + node.children.asSequence().flatMap { all(it) }

    @Test
    fun plainTextWithSignsAndLineBreaksShowsAsWritten() {
        for (text in listOf(
            "x = 5 and 2+2=4, a==b, key=value&k2=v2, x => y",
            "Costs \$5, 3 < 4 & 5 > 2, 'quoted' \"double\" (paren) [bracket] a: b! c",
            "Buy:\nmilk\neggs",
        )) {
            assertEquals(text, paragraphText(text))
        }
    }

    @Test
    fun htmlHighlightImagesAndAngleLinksShowAsWritten() {
        assertEquals("a <b>bold</b> and <span class=\"x\">span</span> word", paragraphText("a <b>bold</b> and <span class=\"x\">span</span> word"))
        assertEquals("pay ==attention== now", paragraphText("pay ==attention== now"))
        assertEquals("see ![pic](https://example.org/p.png) here", paragraphText("see ![pic](https://example.org/p.png) here"))
        assertEquals("go [x](<https://a.com/b>) now", paragraphText("go [x](<https://a.com/b>) now"))
    }

    @Test
    fun markupShowsItsTextAndCodeKeepsItsSigns() {
        assertEquals("Call Sam about the budget and old plan", paragraphText("Call **Sam** about the _budget_ and ~~old~~ plan"))
        assertEquals("run  a=b  now", paragraphText("run `a=b` now"))
        assertEquals("the docs or https://example.org/x", paragraphText("[the docs](https://example.org) or <https://example.org/x>"))
    }

    @Test
    fun aNoteLinkOpensAsABrowsableViewWithItsSchemeLowercased() {
        val intent = linkIntent("HTTPS://example.org/a")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertEquals("https://example.org/a", intent.dataString)
    }
}
