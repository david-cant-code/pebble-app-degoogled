package com.anopticlabs.gravel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.ui.Section
import coredevices.pebble.ui.SettingsItem
import coredevices.pebble.ui.SnackbarDisplay
import coredevices.pebble.ui.TopLevelType
import coredevices.pebble.ui.basicSettingsActionItem
import coredevices.pebble.ui.quickLaunchOptions
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private sealed interface WatchAppQuestion {
    data class Install(val offer: WatchAppOffer, val bytes: Long?) : WatchAppQuestion

    /** [sha256] is the copy the question shows, which is the one a yes trusts. */
    data class Trust(val sha256: String) : WatchAppQuestion

    data object Remove : WatchAppQuestion

    data object QuickLaunch : WatchAppQuestion
}

/**
 * Gravel: the Notebook watch app's rows in Settings > Notebook. The dialogs they open are composed where this is
 * called, and each action's result line goes to [snackbarDisplay].
 */
@Composable
fun notebookWatchAppSettings(tab: NotebookTab?, notebookShown: Boolean, snackbarDisplay: SnackbarDisplay): List<SettingsItem> {
    val notebook = tab?.takeIf { it.supported } ?: return emptyList()
    val watchApp by notebook.watchApp.collectAsState()
    val buttonsCanBeSet = rememberWatchButtonsCanBeSet()
    val prefs by rememberLibPebble().watchPrefs.collectAsState(emptyList())
    // Read when an install ends, which is later than the tap that started it.
    val offerButton by rememberUpdatedState(buttonsCanBeSet && !aHoldButtonOpens(prefs, notebook.watchAppId))
    val canRecord by produceState<Boolean?>(null, notebook) { value = notebook.recordingSupported() }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<WatchAppQuestion?>(null) }
    LaunchedEffect(notebook) { notebook.rereadWatchApp() }
    LaunchedEffect(watchApp.message) {
        watchApp.message?.let {
            notebook.watchAppResultShown(it)
            snackbarDisplay.showSnackbar(it)
        }
    }
    when (val asked = question) {
        null -> Unit
        is WatchAppQuestion.Install -> WatchAppInstallQuestion(
            asked.offer,
            asked.bytes,
            onConfirm = {
                question = null
                notebook.installWatchApp()
                // An install that started is busy from the call on, so the first value that is not busy is its end.
                scope.launch {
                    val after = notebook.watchApp.first { !it.busy }
                    if (after.status == WatchAppStatus.INSTALLED && offerButton) question = WatchAppQuestion.QuickLaunch
                }
            },
            onDismiss = { question = null },
        )
        is WatchAppQuestion.Trust -> AlertDialog(
            onDismissRequest = { question = null },
            title = { Text("Trust this watch app?") },
            text = {
                Column {
                    Text(
                        "Do this only for a copy you built or changed yourself. It will be able to save recordings to the " +
                            "Notebook, as Gravel's own copy can.\n\nThis copy's SHA-256:",
                    )
                    Text(digestInFours(asked.sha256), fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    question = null
                    notebook.trustWatchAppCopy(asked.sha256)
                }) { Text("Trust") }
            },
            dismissButton = { TextButton(onClick = { question = null }) { Text("Cancel") } },
        )
        WatchAppQuestion.Remove -> AlertDialog(
            onDismissRequest = { question = null },
            title = { Text("Remove the Notebook watch app?") },
            text = { Text("It is taken off the watch, and a Quick Launch button set to it is turned off.") },
            confirmButton = {
                TextButton(onClick = {
                    question = null
                    notebook.removeWatchApp()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { question = null }) { Text("Cancel") } },
        )
        WatchAppQuestion.QuickLaunch -> WatchButtonPicker(notebook.watchAppId) { question = null }
    }
    return remember(watchApp, notebookShown, buttonsCanBeSet, canRecord) {
        val line = watchAppStatusLine(watchApp)
        // On a phone that cannot record, every watch recording is refused.
        val offer = watchAppOffer(watchApp).takeIf { canRecord == true }
        val inLocker = watchAppInLocker(watchApp)
        listOf(
            basicSettingsActionItem(
                title = "Notebook Watch App",
                description = watchAppRowDescription(watchApp, offer),
                topLevelType = TopLevelType.Phone,
                section = Section.Notebook,
                keywords = "notebook watch app install update pebble",
                show = { notebookShown && line != null },
                action = offer?.let { { question = WatchAppQuestion.Install(it, watchApp.offeredBytes) } },
            ),
            basicSettingsActionItem(
                title = "Trust This Copy",
                description = "Let the watch app copy now installed save recordings",
                topLevelType = TopLevelType.Phone,
                section = Section.Notebook,
                keywords = "notebook watch app trust own build modified",
                show = { notebookShown && watchAppCanBeTrusted(watchApp) && !watchApp.busy },
                action = { watchApp.sha256?.let { question = WatchAppQuestion.Trust(it) } },
            ),
            basicSettingsActionItem(
                title = "Stop Trusting This Copy",
                description = "Recordings from the watch app copy now installed are no longer saved",
                topLevelType = TopLevelType.Phone,
                section = Section.Notebook,
                keywords = "notebook watch app trust own build modified",
                show = { notebookShown && watchApp.status == WatchAppStatus.USER_TRUSTED && !watchApp.busy },
                action = { notebook.stopTrustingWatchAppCopy() },
            ),
            basicSettingsActionItem(
                title = "Record with a Watch Button",
                description = "Set a button on the watch that starts a recording when held",
                topLevelType = TopLevelType.Phone,
                section = Section.Notebook,
                keywords = "notebook watch app quick launch button hold",
                show = { notebookShown && inLocker && buttonsCanBeSet },
                action = { question = WatchAppQuestion.QuickLaunch },
            ),
            basicSettingsActionItem(
                title = "Remove Watch App",
                description = "Take the Notebook app off the watch",
                topLevelType = TopLevelType.Phone,
                section = Section.Notebook,
                keywords = "notebook watch app remove uninstall delete",
                show = { notebookShown && inLocker && !watchApp.busy },
                action = { question = WatchAppQuestion.Remove },
            ),
        )
    }
}

/** Whether a connected watch takes a Quick Launch button set from the phone ([watchButtonsCanBeSet]). */
@Composable
internal fun rememberWatchButtonsCanBeSet(): Boolean {
    val libPebble = rememberLibPebble()
    val watches by libPebble.watches.collectAsState()
    val config by libPebble.config.collectAsState()
    return watchButtonsCanBeSet(
        watches.filterIsInstance<ConnectedPebbleDevice>().map { it.capabilities },
        config.watchConfig.enableWatchSettingsSync,
    )
}

/** Asks before an install, update or replace, with the download's size when it is known. */
@Composable
internal fun WatchAppInstallQuestion(offer: WatchAppOffer, bytes: Long?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(offer.question) },
        text = { Text(bytes?.let { WatchAppText.download(it) }.orEmpty()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(offer.label) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The four hold buttons with what each opens now; a tap sets that button to the app [appId] and nothing is picked beforehand. */
@Composable
internal fun WatchButtonPicker(appId: Uuid, onDismiss: () -> Unit) {
    val libPebble = rememberLibPebble()
    val prefs by libPebble.watchPrefs.collectAsState(emptyList())
    val names = quickLaunchOptions(libPebble).associate { it.uuid to it.displayName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record with a watch button?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Holding the button you pick starts a recording at once. Without one, open Notebook in the watch's app list to record.")
                quickLaunchRows(prefs, names, appId).forEach { row ->
                    ListItem(
                        headlineContent = { Text(row.name) },
                        supportingContent = { Text(if (row.opensThisApp) "Starts a recording" else "Now: ${row.opensNow}") },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !row.opensThisApp) {
                            libPebble.setWatchPref(WatchPreference(row.button, QuickLaunchSetting(enabled = true, uuid = appId)))
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
