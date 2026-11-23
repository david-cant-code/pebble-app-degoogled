package com.anopticlabs.gravel.accessibility

import android.graphics.Bitmap
import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.debug.LARGEST_FONT_SCALE
import com.anopticlabs.gravel.debug.LargestFontTestActivity
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import theme.AppTheme
import theme.CoreAppColorScheme
import theme.OnboardingTheme
import kotlin.test.assertTrue

/** The contrast floor Gravel holds for text of every size. */
const val MIN_TEXT_CONTRAST = 4.5

/** Colors covering less than this share of a region are not taken as its text. */
private const val INK_MIN_SHARE = 0.005

private const val OPAQUE = 0xFF000000.toInt()

/** A region as drawn: its commonest color as the [background], the color with most contrast against it as the [ink]. */
data class DrawnColors(val background: Int, val ink: Int) {
    val ratio: Double get() = contrast(ink, background)
}

/** The colors of [bitmap]'s pixels outside [leftOut], or null when none is left or no color covers [INK_MIN_SHARE]. */
fun drawnColors(bitmap: Bitmap, leftOut: List<Rect> = emptyList()): DrawnColors? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val counts = HashMap<Int, Int>()
    var kept = 0
    for (i in pixels.indices) {
        val x = i % bitmap.width + 0.5f
        val y = i / bitmap.width + 0.5f
        if (leftOut.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }) continue
        val opaque = pixels[i] or OPAQUE
        counts[opaque] = (counts[opaque] ?: 0) + 1
        kept++
    }
    if (kept == 0) return null
    val background = counts.maxBy { it.value }.key
    val floor = maxOf(1, (kept * INK_MIN_SHARE).toInt())
    val ink = counts.filterValues { it >= floor }.keys.maxByOrNull { contrast(it, background) } ?: return null
    return DrawnColors(background, ink)
}

/** WCAG contrast ratio of two opaque colors. */
fun contrast(a: Int, b: Int): Double {
    val la = Color(a).luminance() + 0.05
    val lb = Color(b).luminance() + 0.05
    return maxOf(la, lb) / minOf(la, lb)
}

/** What one run of a drawn-text test checks; the piece tests run once per check. */
enum class DrawnCheck { CONTRAST, FONT }

/** A theme a piece is drawn in: an app theme, or setup's own scheme, which it draws over any app theme (OnboardingScreen). */
enum class DrawnTheme(val scheme: CoreAppColorScheme?) {
    Light(CoreAppColorScheme.Light), Dark(CoreAppColorScheme.Grey), Black(CoreAppColorScheme.Black), Setup(null)
}

/**
 * Draws one piece of the app on the phone and checks it as drawn.
 *
 * [DrawnCheck.CONTRAST] measures each text against [MIN_TEXT_CONTRAST] in every theme the piece is drawn in: a text's
 * box as drawn (its strongest color against its commonest); in a styled text also each of its colors as laid out
 * against that commonest color; a text field's typed value by its laid-out colors against the field's commonest color
 * once the rows of its descendant texts (label, placeholder, supporting text) are left out. Characters past the last
 * shown line, or after an end ellipsis, are left out. Text that is part of a disabled control (the nearest node holding
 * it with a Role, a click action or a toggle state) is exempt, as WCAG 2.2 exempts text that is part of an inactive
 * user interface component (1.4.3 Contrast (Minimum), "Incidental"). [DrawnCheck.FONT] draws the piece in
 * [LargestFontTestActivity] and fails each text that is cut off (a line wider than its box included), sliced, broken
 * where Unicode line breaking offers no break (inside a word or a number), hidden by what holds it, or drawn over
 * another node with text, a content description, a Role, a progress range or a toggle state; a line shortened with
 * "..." passes.
 *
 * Not seen by the contrast check: text without Text semantics (under clearAndSetSemantics, drawn on a Canvas) unless a
 * test passes its node as `also`; anything else drawn inside a plain text's own box; what drawing changes after layout
 * in a text field's value or a styled text's colors (a fading layer or overlay, a brush); a span's own background
 * color; text on a see-through part of a popup (a captured pixel's alpha is dropped); the extended colors a setup piece
 * would take under Dark or Black (setup pieces are drawn over the Light app theme only). Not seen by the font check:
 * text without Text semantics; overlaps inside a text field, and with nodes outside the list above (decorative icons,
 * dividers); unwrapped text past its first line. The font check judges breaks by Unicode line breaking alone, in URLs
 * and email addresses too. A piece holding a lazy list longer than the screen is not supported. Emoji in a colored span
 * are measured in the span's color.
 */
class DrawnText(val check: DrawnCheck) : TestRule {

    val compose: ComposeContentTestRule = when (check) {
        DrawnCheck.CONTRAST -> createComposeRule()
        DrawnCheck.FONT -> createAndroidComposeRule<LargestFontTestActivity>()
    }

    override fun apply(base: Statement, description: Description): Statement = compose.apply(base, description)

    private var themes = listOf(DrawnTheme.Light)
    private var theme by mutableStateOf(DrawnTheme.Light)

    /** Sets [content] on a page like the app's screens, in the Light, Dark and Black themes (Light for the font check). */
    fun setContent(content: @Composable () -> Unit) =
        show(listOf(DrawnTheme.Light, DrawnTheme.Dark, DrawnTheme.Black), content)

    /** Sets [content] on a page as setup draws its pages. */
    fun setSetupContent(content: @Composable () -> Unit) = show(listOf(DrawnTheme.Setup), content)

    private fun show(drawnIn: List<DrawnTheme>, content: @Composable () -> Unit) {
        themes = drawnIn
        theme = drawnIn.first()
        compose.setContent {
            Themed(theme) { Scaffold { padding -> Box(Modifier.padding(padding)) { content() } } }
        }
    }

    @Composable
    private fun Themed(theme: DrawnTheme, content: @Composable () -> Unit) {
        val scheme = theme.scheme
        if (scheme != null) {
            AppTheme(scheme, content)
        } else {
            AppTheme(CoreAppColorScheme.Light) { OnboardingTheme(content) }
        }
    }

    /** This run's check, failing once with every finding. [also] as in [contrastFailures]. */
    fun assertReadable(vararg also: SemanticsMatcher) {
        val failures = if (check == DrawnCheck.CONTRAST) contrastFailures(*also) else fontFailures()
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The contrast check alone, skipped in the font run: for a piece with no text, such as a button showing only a
     * spinner, text without Text semantics measured through [also], or text still cut off at the largest font.
     */
    fun assertContrast(vararg also: SemanticsMatcher) {
        assumeTrue("contrast only", check == DrawnCheck.CONTRAST)
        assertReadable(*also)
    }

    /**
     * Every text not exempt that is under [MIN_TEXT_CONTRAST] in each theme, plus each node [also] matches (a spinner,
     * an icon), measured whole and exempt or not.
     */
    fun contrastFailures(vararg also: SemanticsMatcher): List<String> {
        val failures = mutableListOf<String>()
        for (each in themes) {
            theme = each
            compose.waitForIdle()
            val texts = textNodes().filterNot(::inDisabledControl)
            val extras = also.flatMap { matcher ->
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .also { if (it.isEmpty()) failures += "$each: nothing is ${matcher.description}" }
            }
            if (texts.isEmpty() && extras.isEmpty()) failures += "$each: no enabled text to measure"
            val measured = texts.flatMap(::measureText) + extras.map { label(it) to drawnColors(capture(it)) }
            for ((what, drawn) in measured) {
                if (drawn == null) {
                    failures += "$each: $what: nothing to measure"
                } else if (drawn.ratio < MIN_TEXT_CONTRAST) {
                    failures += "$each: $what is ${"%.2f".format(drawn.ratio)}:1 (${hex(drawn.ink)} on ${hex(drawn.background)})"
                }
            }
        }
        theme = themes.first()
        compose.waitForIdle()
        return failures
    }

    /** Each text that does not fit at [LARGEST_FONT_SCALE], disabled text included, checked scrolled into view. */
    fun fontFailures(): List<String> {
        kotlin.check(check == DrawnCheck.FONT) { "the font check runs in LargestFontTestActivity" }
        compose.waitForIdle()
        val failures = mutableListOf<String>()
        val ids = textNodes().map { it.id }
        if (ids.isEmpty()) failures += "no text to check"
        val overlaps = mutableSetOf<Pair<Int, Int>>()
        for (id in ids) {
            val node = shown(byId(id)).fetchSemanticsNode()
            val layout = layoutOf(node)
            if (layout == null) {
                failures += "${label(node)}: no text layout to check"
            } else if (layout.layoutInput.density.fontScale != LARGEST_FONT_SCALE) {
                failures += "${label(node)}: drawn at font ${layout.layoutInput.density.fontScale}"
            } else {
                layoutProblem(layout)?.let { failures += "${label(node)}: $it" }
            }
            if (hidden(node, layout)) failures += "${label(node)}: partly hidden"
            for (other in drawnThings()) {
                if (other.id == id || rootOf(other).id != rootOf(node).id || related(node, other)) continue
                val both = node.boundsInRoot.intersect(other.boundsInRoot)
                if (both.width > 1f && both.height > 1f && overlaps.add(minOf(id, other.id) to maxOf(id, other.id))) {
                    failures += "${label(node)} overlaps ${label(other)}"
                }
            }
        }
        return failures.map { "font $LARGEST_FONT_SCALE: $it" }
    }

    /** A text's box as drawn and each of its laid-out colors, or a text field's typed value by its laid-out colors. */
    private fun measureText(node: SemanticsNode): List<Pair<String, DrawnColors?>> {
        val bitmap = capture(node)
        val shownNode = byIdNode(node.id)
        val layout = layoutOf(shownNode)
        val value = node.config.getOrNull(SemanticsProperties.EditableText)
        if (value != null && value.text.isEmpty()) return emptyList()
        val field = value != null
        // A field's descendant texts sit in rows of its box, and a long supporting text could outweigh the container.
        val origin = shownNode.boundsInRoot.topLeft
        val texts = if (field) descendants(shownNode).filter { hasText.matches(it) } else emptyList()
        val rows = texts.map { it.boundsInRoot.translate(-origin) }.map { Rect(0f, it.top, bitmap.width.toFloat(), it.bottom) }
        val drawn = drawnColors(bitmap, rows)
        val text = layout?.layoutInput?.text
        val spans = text?.spanStyles?.filter { it.item.color.isSpecified && it.start < it.end }.orEmpty()
        val whole = if (field) emptyList() else listOf(label(node) to drawn)
        if (text == null || drawn == null || (!field && spans.isEmpty())) {
            return if (field) listOf(label(node) to null) else whole
        }
        val base = layout.layoutInput.style.color
        if (spans.isEmpty()) return whole + listOf(label(node) to laidOut(base, drawn.background))
        val shown = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
        // A character is drawn in the color of the last span covering it, or in the text's own color.
        val colorOf = (0 until shown).groupBy { i -> spans.lastOrNull { i in it.start until it.end }?.item?.color }
        return whole + colorOf.mapNotNull { (color, chars) ->
            val runs = chars.fold(mutableListOf<IntRange>()) { acc, i ->
                if (acc.isNotEmpty() && acc.last().last == i - 1) acc[acc.lastIndex] = acc.last().first..i else acc += i..i
                acc
            }
            val words = runs.map { text.substring(it.first, it.last + 1).trim() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return@mapNotNull null
            val what = "${label(node)} ${if (color == null) "rest" else "part"} \"${words.joinToString(" ... ")}\""
            what to laidOut(color ?: base, drawn.background)
        }
    }

    /** [color], composited, as the ink on [background]; null when the layout names no color. */
    private fun laidOut(color: Color?, background: Int): DrawnColors? =
        color?.takeIf { it.isSpecified }?.let { DrawnColors(background, it.compositeOver(Color(background)).toArgb() or OPAQUE) }

    // For plain-string text the action lays the text out again at its incoming maximum width, wrapping whatever
    // softWrap says (androidx compose foundation 1.11.2, ParagraphLayoutCache.slowCreateTextLayoutResultOrNull), so a
    // single drawn line is judged by the text's full width, and a wrapped line by its width, as alignment there can
    // move it.
    private fun layoutProblem(layout: TextLayoutResult): String? {
        val input = layout.layoutInput
        val lastBottom = layout.getLineBottom(if (input.softWrap) layout.lineCount - 1 else 0)
        if (lastBottom > layout.size.height + 1) return "sliced: its lines need ${lastBottom.toInt()} px of height, has ${layout.size.height}"
        val ellipsized = (0 until layout.lineCount).any(layout::isLineEllipsized)
        if (!input.softWrap) {
            val needed = layout.multiParagraph.intrinsics.maxIntrinsicWidth
            return if (needed > layout.size.width + 1 && input.overflow !in ellipses) {
                "cut off: needs ${needed.toInt()} px of width, has ${layout.size.width}"
            } else {
                null
            }
        }
        if (layout.multiParagraph.didExceedMaxLines && !ellipsized) return "cut off: lines past its last are dropped"
        for (line in 0 until layout.lineCount) {
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            if (!layout.isLineEllipsized(line) && width > layout.size.width + 1) {
                return "cut off: line ${line + 1} needs ${width.toInt()} px of width, has ${layout.size.width}"
            }
        }
        val text = input.text.text
        // A break the line breaker made where Unicode line breaking offers none splits a word or a number.
        val breaks = BreakIterator.getLineInstance().apply { setText(text) }
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            if (end in 1 until text.length && !breaks.isBoundary(end)) {
                return "broken inside a word at \"${text.substring(maxOf(0, end - 8), minOf(text.length, end + 8))}\""
            }
        }
        return null
    }

    // boundsInRoot comes clipped only by ancestors that clip (androidx compose ui 1.11.2, NodeCoordinator.rectInParent
    // intersects with a coordinator's size only for a clipping layer); the root's own bounds are applied here.
    private fun hidden(node: SemanticsNode, layout: TextLayoutResult?): Boolean {
        val shown = node.boundsInRoot.intersect(rootOf(node).boundsInRoot)
        // Text longer than the scrolling area holding it is read by scrolling, provided the area shows two of its lines
        // (or, scrolling sideways, MIN_SIDEWAYS_VIEW of it).
        val twoLines = layout?.let { it.getLineBottom(minOf(1, it.lineCount - 1)) } ?: 0f
        val sideways = with(compose.density) { MIN_SIDEWAYS_VIEW.toPx() }
        val tall = ancestors(node).firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
        val wide = ancestors(node).firstOrNull { SemanticsProperties.HorizontalScrollAxisRange in it.config }
        val width = minOf(node.size.width.toFloat(), wide?.let { maxOf(it.boundsInRoot.width, sideways) } ?: Float.MAX_VALUE)
        val height = minOf(node.size.height.toFloat(), tall?.let { maxOf(it.boundsInRoot.height, twoLines) } ?: Float.MAX_VALUE)
        return shown.width < width - 1 || shown.height < height - 1
    }

    // The nearest control holding the text, itself included, decides: text is exempt when that control is disabled.
    private fun inDisabledControl(node: SemanticsNode) = generateSequence(node) { it.parent }
        .firstOrNull { c -> controlKeys.any { it in c.config } }
        ?.let { SemanticsProperties.Disabled in it.config } == true

    private fun textNodes(): List<SemanticsNode> = compose.onAllNodes(hasText, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)

    /** The nodes the overlap check compares text with, as [drawsSomething] lists them. */
    private fun drawnThings(): List<SemanticsNode> = compose.onAllNodes(drawsSomething, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        return results.takeIf { getLayout?.invoke(it) == true }?.firstOrNull()
    }

    private fun capture(node: SemanticsNode): Bitmap = shown(byId(node.id)).captureToImage().asAndroidBitmap()

    private fun byId(id: Int) = SemanticsMatcher("node $id") { it.id == id }

    private fun byIdNode(id: Int) = compose.onNode(byId(id), useUnmergedTree = true).fetchSemanticsNode()

    /** The node's interaction, scrolled into view first when it sits in something that scrolls. */
    private fun shown(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val interaction = compose.onNode(matcher, useUnmergedTree = true)
        if (ancestors(interaction.fetchSemanticsNode()).any { SemanticsActions.ScrollBy in it.config }) interaction.performScrollTo()
        return interaction
    }

    private fun ancestors(node: SemanticsNode) = generateSequence(node.parent) { it.parent }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = node.children.flatMap { listOf(it) + descendants(it) }

    private fun rootOf(node: SemanticsNode) = generateSequence(node) { it.parent }.last()

    private fun related(a: SemanticsNode, b: SemanticsNode) = ancestors(a).any { it.id == b.id } || ancestors(b).any { it.id == a.id }

    private fun label(node: SemanticsNode): String {
        val config = node.config
        val text = config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: if (SemanticsProperties.ProgressBarRangeInfo in config) "spinner" else "node ${node.id}"
        return "\"$text\""
    }

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    private companion object {
        val MIN_SIDEWAYS_VIEW = 48.dp
        val ellipses = setOf(TextOverflow.Ellipsis, TextOverflow.StartEllipsis, TextOverflow.MiddleEllipsis)
        val controlKeys = listOf(SemanticsProperties.Role, SemanticsActions.OnClick, SemanticsProperties.ToggleableState)
        val hasText = SemanticsMatcher("text") {
            SemanticsProperties.Text in it.config || SemanticsProperties.EditableText in it.config
        }
        val drawsSomething = SemanticsMatcher("text, a control or an icon") {
            val c = it.config
            SemanticsProperties.Text in c || SemanticsProperties.EditableText in c || SemanticsProperties.ContentDescription in c ||
                SemanticsProperties.Role in c || SemanticsProperties.ProgressBarRangeInfo in c || SemanticsProperties.ToggleableState in c
        }
    }
}
