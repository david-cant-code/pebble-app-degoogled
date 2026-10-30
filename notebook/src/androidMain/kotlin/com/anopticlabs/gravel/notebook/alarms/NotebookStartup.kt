package com.anopticlabs.gravel.notebook.alarms

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.isNotebookSupported
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.sources.PhoneRecorder
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the Notebook does when the app process starts: re-arm from the stores, since a force stop
 * drops every alarm, load the notification link token before a tap needs it on the main thread,
 * start the capture coordinator with a first run, let the recorder watch the activities, start
 * automatic backups ([startBackups]), the charger watch for long captures ([startCharger]), the
 * network watch for captures waiting on the server ([startNetworkWatch]) and the watch for a changed speech
 * setting or a speech model's finished download ([startSpeechWatch]), and run the backups' checks each time the app
 * comes to the front.
 */
class NotebookStartup(
    private val alarms: ReminderAlarms,
    private val linkToken: NotebookLinkToken,
    private val coordinator: CaptureCoordinator,
    private val visibility: AppVisibility,
    private val recorder: PhoneRecorder,
    private val startBackups: () -> Unit = {},
    private val checkBackups: () -> Unit = {},
    private val startCharger: () -> Unit = {},
    private val startNetworkWatch: () -> Unit = {},
    private val startSpeechWatch: () -> Unit = {},
    // The default logs a throw out of the sweep: the process is not ended over a store the sweep could not use.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e -> Logger.withTag("NotebookStartup").w(e) { "startup sweep failed" } }),
    private val supported: () -> Boolean = { isNotebookSupported },
) {

    fun start() {
        if (!supported()) return
        // First, so the backups' change watch sees the re-arm's writes.
        startBackups()
        scope.launch { linkToken.value }
        scope.launch { alarms.rearmAll() }
        coordinator.start(visibility)
        recorder.start(visibility)
        startCharger()
        startNetworkWatch()
        startSpeechWatch()
        coordinator.request()
        visibility.addResumedListener { if (it) checkBackups() }
    }
}
