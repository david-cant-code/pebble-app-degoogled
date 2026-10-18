package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.notes.NoteDisplay
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** How a note's body is laid out: as markdown, or as plain text in pieces. */
internal sealed interface NoteRendering {
    data class Markdown(val state: State.Success) : NoteRendering

    data class Plain(val chunks: List<String>) : NoteRendering
}

/**
 * Keeps no links: the renderer otherwise stores every link it draws under its text and opens the
 * stored destination for a tapped link of that text (renderer 0.45.0, annotatorSettings). With it,
 * a tapped link opens its own destination and reference-style links do not resolve.
 */
object NoteLinks : ReferenceLinkHandler {
    override fun store(label: String, destination: String?) = Unit

    // Empty means the tapped link's own destination to the renderer's listener (renderer 0.45.0, annotatorSettings).
    override fun find(label: String): String = ""
}

/**
 * [body] parsed; plain when [NoteRenderLimits.mayParse] refuses it, the parse fails or runs past
 * [NoteRenderLimits.PARSE_BUDGET_MILLIS] or [keepGoing], or the tree is past [NoteRenderLimits.fits].
 */
internal fun renderingOf(body: String, keepGoing: () -> Boolean = { true }): NoteRendering {
    if (NoteRenderLimits.mayParse(body)) {
        val deadline = System.nanoTime() + NoteRenderLimits.PARSE_BUDGET_MILLIS * 1_000_000
        val flavour = GFMFlavourDescriptor()
        val token = NoteRenderLimits.parseBudget { keepGoing() && System.nanoTime() < deadline }
        val parser = MarkdownParser(flavour, cancellationToken = token)
        val state = parseMarkdown(body, lookupLinks = false, flavour = flavour, parser = parser, referenceLinkHandler = NoteLinks)
        if (state is State.Success && NoteRenderLimits.fits(state.node)) return NoteRendering.Markdown(state)
    }
    return NoteRendering.Plain(NoteDisplay.chunks(body))
}

/** The note page's annotator: single line breaks kept, and [NoteRenderLimits.appendAsWritten]. */
val noteAnnotator: MarkdownAnnotator = markdownAnnotator(
    config = markdownAnnotatorConfig(eolAsNewLine = true),
    annotate = { content, child -> NoteRenderLimits.appendAsWritten(this, content, child) },
)

/**
 * A note's [body], parsed off the main thread and laid out in a lazy list. A tapped link goes to
 * [onLink], which the renderer reaches through [LocalUriHandler] (renderer 0.45.0,
 * annotatorSettings); images show as the text they are written as.
 */
@Composable
fun NoteBody(body: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    var rendering by remember(body) { mutableStateOf<NoteRendering?>(null) }
    LaunchedEffect(body) {
        val job = currentCoroutineContext()[Job]
        rendering = withContext(Dispatchers.Default) { renderingOf(body) { job?.isActive != false } }
    }
    val padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    when (val shown = rendering) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is NoteRendering.Plain -> LazyColumn(modifier, contentPadding = padding) {
            items(shown.chunks) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        is NoteRendering.Markdown -> {
            val links = remember(onLink) {
                object : UriHandler {
                    override fun openUri(uri: String) = onLink(uri)
                }
            }
            CompositionLocalProvider(LocalUriHandler provides links) {
                Markdown(
                    state = shown.state,
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.headlineSmall,
                        h2 = MaterialTheme.typography.titleLarge,
                        h3 = MaterialTheme.typography.titleMedium,
                        h4 = MaterialTheme.typography.titleSmall,
                        h5 = MaterialTheme.typography.labelLarge,
                        h6 = MaterialTheme.typography.labelMedium,
                        text = MaterialTheme.typography.bodyMedium,
                        paragraph = MaterialTheme.typography.bodyMedium,
                        ordered = MaterialTheme.typography.bodyMedium,
                        bullet = MaterialTheme.typography.bodyMedium,
                        list = MaterialTheme.typography.bodyMedium,
                    ),
                    annotator = noteAnnotator,
                    components = noteComponents(),
                    modifier = modifier,
                    success = { state, components, mod -> LazyMarkdownSuccess(state, components, mod, padding) },
                )
            }
        }
    }
}

/**
 * The renderer's components with HTML blocks and link definitions shown as their text.
 * Once a custom component is set the renderer hands it every node type it has no component for
 * (renderer 0.45.0, MarkdownElement); the others render their children without a spacer, as the
 * renderer's own fallback does for a top-level item.
 */
@Composable
private fun noteComponents(): MarkdownComponents {
    val self = remember { arrayOfNulls<MarkdownComponents>(1) }
    return remember {
        markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            custom = { type, model ->
                if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownElementTypes.LINK_DEFINITION) {
                    Text(NoteRenderLimits.blockText(model.node, model.content), style = model.typography.text)
                } else {
                    model.node.children.forEach { MarkdownElement(it, self[0]!!, model.content, includeSpacer = false) }
                }
            },
        ).also { self[0] = it }
    }
}
