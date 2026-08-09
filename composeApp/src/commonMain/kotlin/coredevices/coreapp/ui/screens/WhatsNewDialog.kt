package coredevices.coreapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigHolder
import org.koin.compose.koinInject

/**
 * Fork: the current "What's New" changelog revision. Bump this by one, and prepend an
 * entry to [whatsNewEntries], whenever there is something worth announcing to existing
 * users on update. The dialog shows once per user per bump (see [WhatsNewDialog]).
 */
const val WHATS_NEW_VERSION = 1

/** A single announced change: a short heading and a sentence or two of body. */
data class WhatsNewEntry(val title: String, val body: String)

/**
 * Newest first. Kept deliberately small: this is a "what changed for you" notice, not a
 * full changelog (that lives in git history and the repo docs).
 */
val whatsNewEntries: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        title = "Control what watchfaces and apps can reach",
        body = "Watchfaces and apps can run code on your phone that uses the internet and " +
            "your location. You can now turn that off, for everything or per app, in " +
            "Settings > Apps > Watch App Permissions. It starts off for apps you already " +
            "had installed; turn it on for the ones you trust.",
    ),
)

/**
 * One-time update notice, shown over the home screen. Only fires when onboarding has
 * already happened (so it never overlaps the onboarding privacy choice) and the user's
 * last-seen revision is behind [WHATS_NEW_VERSION]. Dismissing stamps the current version
 * so it does not reappear.
 */
@Composable
fun WhatsNewDialog() {
    val settings: Settings = koinInject()
    val configHolder: CoreConfigHolder = koinInject()
    val config by configHolder.config.collectAsState()

    // Guard: only for installs that finished onboarding in a previous version. A fresh
    // install stamps lastSeenWhatsNewVersion at the end of onboarding, so this is false
    // for it and the dialog stays hidden.
    val onboarded = settings.getBoolean(SHOWN_ONBOARDING, false)
    if (!onboarded || config.lastSeenWhatsNewVersion >= WHATS_NEW_VERSION) {
        return
    }

    AlertDialog(
        onDismissRequest = { /* require an explicit acknowledge so it isn't missed */ },
        title = { Text("What's new") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            TextButton(onClick = {
                configHolder.update(config.copy(lastSeenWhatsNewVersion = WHATS_NEW_VERSION))
            }) { Text("Got it") }
        },
    )
}
