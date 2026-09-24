package com.anopticlabs.gravel.ui

import CoreAppVersion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import coredevices.util.knownIssuesUrl
import org.koin.compose.koinInject

/** Opens the KNOWN_ISSUES.md entry titled [heading] (a coredevices.util.KnownIssue); see knownIssuesUrl. */
@Composable
fun KnownIssuesLink(heading: String, modifier: Modifier = Modifier) {
    val url = knownIssuesUrl(koinInject<CoreAppVersion>().version, heading)
    val uriHandler = LocalUriHandler.current
    var unopened by remember { mutableStateOf(false) }
    Column(modifier) {
        TextButton(onClick = {
            // What openUri throws when no app opens the link (androidx.compose.ui:ui-android 1.11.2,
            // AndroidUriHandler.openUri).
            try {
                uriHandler.openUri(url)
            } catch (_: IllegalArgumentException) {
                unopened = true
            }
        }) {
            Text("More about this")
        }
        if (unopened) {
            SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
