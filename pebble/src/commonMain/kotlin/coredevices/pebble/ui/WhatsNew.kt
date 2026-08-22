package coredevices.pebble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fork: the current "What's New" changelog revision. Bump this by one, and prepend an
 * entry to [whatsNewEntries], whenever there is something worth announcing to existing
 * users on update. The popup auto-shows once per user per bump (`WhatsNewDialog` in
 * composeApp) and can be reopened any time from Settings > About.
 */
const val WHATS_NEW_VERSION = 3

/** A single announced change: a short heading and a sentence or two of body. */
data class WhatsNewEntry(val title: String, val body: String)

/**
 * Newest first, one entry per revision bump (WhatsNewTest pins that convention).
 * Kept deliberately small: this is a "what changed for you" notice, not a full
 * changelog (that lives in git history and the repo docs).
 */
val whatsNewEntries: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        title = "Store search that waits for you",
        body = "Searching the store now runs when you tap search, not while you type, " +
            "so the store's search provider (Algolia) only ever sees what you submit, " +
            "and it is asked not to keep analytics about it. Your own watchfaces and " +
            "apps still filter as you type.",
    ),
    WhatsNewEntry(
        title = "Changelogs and help that open properly",
        body = "\"What's new in the app\" in Settings > About now shows Gravel's own " +
            "changes (this notice), any time you want to reread it. PebbleOS release " +
            "notes and the help centre open in your browser, replacing an in-app view " +
            "that refused to load those pages.",
    ),
    WhatsNewEntry(
        title = "Control what watchfaces and apps can reach",
        body = "Watchfaces and apps can run code on your phone that uses the internet and " +
            "your location. You can now turn that off, for everything or per app, in " +
            "Settings > Apps > Watch App Permissions. It starts off for apps you already " +
            "had installed; turn it on for the ones you trust.",
    ),
)

/**
 * The What's-new popup, shared by the update-triggered notice (`WhatsNewDialog` in
 * composeApp) and the Settings > About entry. [requireExplicitAck] disables
 * tap-outside and back dismissal so the update notice cannot be swallowed by a
 * stray tap; the on-demand path passes false and dismisses normally. [onClose]
 * runs on any close and is where callers stamp the revision as seen.
 */
@Composable
fun WhatsNewPopup(requireExplicitAck: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!requireExplicitAck) onClose() },
        title = { Text("What's new") },
        text = {
            // Scrolls because the list accumulates an entry per revision and the
            // on-demand path shows the full history.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                whatsNewEntries.forEach { entry ->
                    Column {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Got it") }
        },
    )
}
