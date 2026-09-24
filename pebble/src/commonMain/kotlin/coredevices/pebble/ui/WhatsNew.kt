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
const val WHATS_NEW_VERSION = 9

/** A single announced change: a short heading and a sentence or two of body. */
data class WhatsNewEntry(val title: String, val body: String)

/**
 * Newest first, one entry per revision bump (WhatsNewTest pins that convention).
 * Kept deliberately small: this is a "what changed for you" notice, not a full
 * changelog (that lives in git history and the repo docs).
 */
val whatsNewEntries: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        title = "The UDP block is off for now",
        body = "Gravel 0.3.1 closed right after opening on LineageOS and systems built on " +
            "it, because its new UDP block also stopped the phone's name lookups there. " +
            "This version turns the UDP block off on every phone until Gravel can tell " +
            "where it stops name lookups. While it is off, a watchface or app with " +
            "internet access turned off does not run its code inside Gravel at all, and " +
            "web pages inside Gravel have HTTP/3 and WebRTC over UDP again.",
    ),
    WhatsNewEntry(
        title = "Internet access off covers more than web requests",
        body = "With internet access turned off for a watchface or app, Gravel already " +
            "refused the web requests of the code it runs. Connections that are not web " +
            "requests, WebRTC for example, now have two layers of their own: Gravel " +
            "blocks UDP sockets in its own process, and an app with internet access off " +
            "runs in a sandboxed page set to allow no connections. Web pages inside " +
            "Gravel lose HTTP/3 and WebRTC over UDP as a result. Changing the setting " +
            "restarts the app's session. Also, Android 17 began permitting plain-HTTP " +
            "connections to services on the phone itself; Gravel denies those again, as " +
            "it does all other plain HTTP.",
    ),
    WhatsNewEntry(
        title = "Companion apps are now your choice",
        body = "Apps made for the original Pebble phone app talk to watchapps through " +
            "classic PebbleKit, which broadcasts what any watchapp or watchface sends " +
            "from the watch to every app on your phone that listens. That is now off by " +
            "default. If a companion app of yours stops receiving watch data, turn it " +
            "back on in Settings > Apps > Watch App Permissions. The newer PebbleKit 2, " +
            "which only reaches apps a watchapp names, stays on and has its own switch. " +
            "Notifications, replies and dictation are not affected.",
    ),
    WhatsNewEntry(
        title = "A safer speech engine",
        body = "The speech engine that transcribes your dictation now runs in an isolated " +
            "process of its own, with no permissions, so a flaw reached through a speech " +
            "model stays contained to that process.",
    ),
    WhatsNewEntry(
        title = "Faster dictation, working in the background, and your own server",
        body = "Speech recognition is optimized: the engine now uses the cores your phone " +
            "actually gives it, and the model picker measures your phone, shows what " +
            "each model costs, and offers a smaller one if needed for your device. " +
            "Dictation with Gravel in the background or the screen locked, which used " +
            "to fail on some phones, now works. And you can connect Gravel to your own " +
            "self-hosted transcription server over https in Settings > Speech " +
            "Recognition; nothing is sent unless you set one up.",
    ),
    WhatsNewEntry(
        title = "Pictures on your wrist, and a store of your choosing",
        body = "Notification images and album art now go to the watch when it asks for " +
            "them, podcasts get seek instead of skip, and the weather app shows tomorrow " +
            "hour by hour. You can add your own app store source in Settings; it is " +
            "fetched only after you confirm it, with no account token and no search " +
            "traffic. Firmware opened from a file now asks before installing.",
    ),
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
        body = "Watchfaces and apps can run code inside Gravel that uses the internet and " +
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
