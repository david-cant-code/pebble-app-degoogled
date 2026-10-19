package com.anopticlabs.gravel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anopticlabs.gravel.models.LlmModelCatalog
import com.anopticlabs.gravel.models.ModelCatalog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelInfo
import coredevices.util.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import coredevices.util.models.CactusSTTMode

/** The speech model the speech page downloads: the recommendation for this phone. */
data class SpeechModelOffer(val info: ModelInfo, val name: String)

/** The app's speech setting and speech model downloads, as the guide's speech page uses them. */
interface SetupSpeech {
    val mode: Flow<CactusSTTMode>
    val download: StateFlow<ModelDownloadStatus>

    suspend fun recommend(): SpeechModelOffer?

    /** Schedules [offer]'s download and makes it the speech model; false when it could not be scheduled. */
    suspend fun download(offer: SpeechModelOffer): Boolean
}

class AppSetupSpeech(private val modelManager: ModelManager, private val config: CoreConfigHolder) : SetupSpeech {
    override val mode: Flow<CactusSTTMode> = config.config.map { it.sttConfig.mode }

    override val download: StateFlow<ModelDownloadStatus> = modelManager.modelDownloadStatus

    // The speed probe runs first, as Settings does before its first pick, so a slow phone is offered a tier it can run.
    override suspend fun recommend(): SpeechModelOffer? = withContext(Dispatchers.Default) {
        modelManager.ensureSpeedMeasured()
        val slug = modelManager.getRecommendedSTTModel().modelSlug
        modelManager.getAvailableSTTModels().firstOrNull { it.slug == slug }
            ?.let { SpeechModelOffer(it, ModelCatalog.byId(it.slug)?.displayName ?: it.slug) }
    }

    override suspend fun download(offer: SpeechModelOffer): Boolean = withContext(Dispatchers.Default) {
        modelManager.downloadSTTModel(offer.info, allowMetered = true).also { started ->
            if (started) {
                val cfg = config.config.value
                config.update(cfg.copy(sttConfig = cfg.sttConfig.copy(modelName = offer.info.slug)))
            }
        }
    }
}

class NotebookSetupViewModel(
    private val tab: NotebookTab,
    private val textModels: TextModels,
    private val speechModels: SetupSpeech,
) : ViewModel() {
    val guide: StateFlow<NotebookSetupState> = tab.setupGuide

    val textFacts: StateFlow<TextModelFacts> = textModels.facts

    private val _steps = MutableStateFlow<List<SetupStep>?>(null)
    val steps: StateFlow<List<SetupStep>?> = _steps.asStateFlow()

    private val _step = MutableStateFlow<SetupStep?>(null)
    val step: StateFlow<SetupStep?> = _step.asStateFlow()

    private val _speechOffer = MutableStateFlow<SpeechModelOffer?>(null)
    val speechOffer: StateFlow<SpeechModelOffer?> = _speechOffer.asStateFlow()

    private val _speechOfferFailed = MutableStateFlow(false)
    val speechOfferFailed: StateFlow<Boolean> = _speechOfferFailed.asStateFlow()

    private val _speechStartFailed = MutableStateFlow(false)
    val speechStartFailed: StateFlow<Boolean> = _speechStartFailed.asStateFlow()

    private val speechRequest = MutableStateFlow<DownloadRequest?>(null)
    private val speechRecheck = MutableStateFlow(0)

    private val _textRequest = MutableStateFlow<DownloadRequest?>(null)
    val textRequest: StateFlow<DownloadRequest?> = _textRequest.asStateFlow()

    val speechDownload: StateFlow<ModelDownloadStatus> = speechModels.download

    // Asked again at each change of the lane, the setting, a request or a recheck, so a finished download reads as ready.
    val speech: StateFlow<SpeechSetup?> =
        combine(speechModels.download, speechModels.mode, speechRequest, speechRecheck) { download, mode, request, _ ->
            speechSetup(tab.canTranscribe(), mode, download, request)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private var started = false
    private var watching = false
    private var run = 0

    /**
     * Reads the phone each time the guide shows: its pages and the page to resume at (where this run is, else the saved
     * page), going back to a model page that would hold Next; the speech offer follows on its own.
     */
    fun start() {
        watchGuide()
        if (started) return
        started = true
        val thisRun = run
        viewModelScope.launch {
            val recording = tab.recordingSupported()
            val canTranscribe = tab.canTranscribe()
            val speechPage = recording && !canTranscribe
            val speechNow = speechSetup(canTranscribe, speechModels.mode.first(), speechModels.download.value, speechRequest.value)
            textModels.refresh()
            val text = withTimeoutOrNull(TEXT_READ_TIMEOUT) { textModels.facts.first { it.read } }
            if (thisRun != run) return@launch
            val unmet = setOfNotNull(
                SetupStep.SPEECH.takeIf { speechPage && speechNow == SpeechSetup.NEEDS_MODEL },
                SetupStep.TEXT_MODEL.takeIf { recording && (text == null || !textModelReady(text, _textRequest.value)) },
            )
            val steps = setupSteps(recording, speechPage)
            _steps.value = steps
            _step.value = resumeStep(_step.value?.name ?: guide.value.step, steps, unmet)
            if (speechPage && _speechOffer.value == null) offerSpeechModel(thisRun)
        }
    }

    /** The guide left the screen: the next [start] reads the phone again and keeps this run's page. */
    fun leave() {
        started = false
    }

    // A finished guide that is not being rerun ends the run: a rerun closed by a notification tap included.
    private fun watchGuide() {
        if (watching) return
        watching = true
        viewModelScope.launch { guide.collect { if (it.done && !it.rerun) reset() } }
    }

    private suspend fun offerSpeechModel(thisRun: Int) {
        val offer = try {
            speechModels.recommend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (thisRun != run) return
        _speechOffer.value = offer
        _speechOfferFailed.value = offer == null
    }

    fun downloadSpeech() {
        val offer = _speechOffer.value ?: return
        val stale = speechModels.download.value as? ModelDownloadStatus.Failed
        viewModelScope.launch {
            val scheduled = speechModels.download(offer)
            _speechStartFailed.value = !scheduled
            if (scheduled) speechRequest.value = DownloadRequest(stale)
        }
    }

    fun downloadText(modelId: String) {
        _textRequest.value = DownloadRequest(textModels.facts.value.download as? ModelDownloadStatus.Failed)
        textModels.download(modelId)
    }

    /** For each time the speech page shows: the speech setting may have changed in Settings meanwhile. */
    fun recheckSpeech() {
        speechRecheck.value++
    }

    /** [from] is the page the button was drawn on, so a second tap before the next page draws moves nothing. */
    fun next(from: SetupStep) = move(from, 1)

    fun back(from: SetupStep) = move(from, -1)

    private fun move(from: SetupStep, by: Int) {
        val steps = _steps.value ?: return
        if (_step.value != from) return
        val target = steps.getOrNull(steps.indexOf(from) + by) ?: return
        _step.value = target
        tab.saveSetupStep(target.name)
    }

    fun backupsNotNow(from: SetupStep) {
        if (_step.value != from) return
        tab.snoozeBackupReminder()
        next(from)
    }

    fun finish() {
        tab.finishSetupGuide()
        reset()
    }

    // Forgets this run, so the next rerun reads the phone again and starts at the first page.
    private fun reset() {
        run++
        started = false
        _steps.value = null
        _step.value = null
        _speechOffer.value = null
        _speechOfferFailed.value = false
        _speechStartFailed.value = false
        speechRequest.value = null
        _textRequest.value = null
    }

    private companion object {
        val TEXT_READ_TIMEOUT = 5.seconds
    }
}

/** The setup guide, page by page, with Back and the page's own buttons below; a rerun can be closed at any page. */
@Composable
fun NotebookSetupGuide(tab: NotebookTab) {
    val viewModel = koinViewModel<NotebookSetupViewModel>()
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }
    val steps by viewModel.steps.collectAsState()
    val step by viewModel.step.collectAsState()
    val guide by viewModel.guide.collectAsState()
    val all = steps
    val current = step
    if (all == null || current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val recording = SetupStep.TYPED_ONLY !in all
    val index = all.indexOf(current)
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step ${index + 1} of ${all.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (guide.rerun) {
                IconButton(onClick = viewModel::finish) { Icon(Icons.Filled.Close, contentDescription = "Close the setup guide") }
            }
        }
        key(current) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    SetupStep.EARLY_BETA -> EarlyBetaPage()
                    SetupStep.WELCOME -> WelcomePage(recording)
                    SetupStep.SPEECH -> SpeechPage(viewModel)
                    SetupStep.TEXT_MODEL -> TextModelPage(viewModel)
                    SetupStep.TYPED_ONLY -> TypedOnlyPage()
                    SetupStep.PROCESSING -> ProcessingPage(tab)
                    SetupStep.QUICK_COMPLETION -> QuickCompletionPage(tab)
                    SetupStep.REMINDER_TIME -> ReminderTimePage(tab)
                    SetupStep.BACKUPS -> BackupsPage(tab)
                    SetupStep.DONE -> DonePage(recording)
                }
            }
        }
        SetupButtons(viewModel, tab, current, first = index == 0, rerun = guide.rerun)
    }
}

@Composable
private fun SetupButtons(viewModel: NotebookSetupViewModel, tab: NotebookTab, step: SetupStep, first: Boolean, rerun: Boolean) {
    val speech by viewModel.speech.collectAsState()
    val offerFailed by viewModel.speechOfferFailed.collectAsState()
    val textFacts by viewModel.textFacts.collectAsState()
    val textRequest by viewModel.textRequest.collectAsState()
    val backups by tab.autoBackup.collectAsState()
    val on = backupsOn(backups)
    val next = canContinue(step, speechCanGoOn(speech, offerFailed), textModelReady(textFacts, textRequest), on)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!first) TextButton(onClick = { viewModel.back(step) }) { Text("Back") }
        Spacer(Modifier.weight(1f))
        when (step) {
            SetupStep.WELCOME -> {
                OutlinedButton(onClick = tab::startRestore) { Text("Restore from a backup") }
                Button(onClick = { viewModel.next(step) }) { Text("Start") }
            }
            SetupStep.BACKUPS -> {
                if (!on) TextButton(onClick = { viewModel.backupsNotNow(step) }) { Text("Not now") }
                Button(onClick = { viewModel.next(step) }, enabled = next) { Text("Next") }
            }
            SetupStep.DONE -> Button(onClick = viewModel::finish) { Text(if (rerun) "Done" else "Open the Notebook") }
            else -> Button(onClick = { viewModel.next(step) }, enabled = next) { Text("Next") }
        }
    }
}

@Composable
private fun PageTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun EarlyBetaPage() {
    PageTitle("Early beta")
    Body("The Notebook is an early beta feature. Expect bugs.")
    Body("If something goes wrong, please report it on Gravel's GitHub page.")
}

@Composable
private fun WelcomePage(recording: Boolean) {
    PageTitle("Set up the Notebook")
    if (recording) {
        Body("The Notebook keeps notes and reminders you record or type. Each recording is turned into text, then into a note or a reminder.")
        Body(
            "A recording becomes a reminder when it starts with a request like \"remind me to call Sam at 5\". A reminder keeps " +
                "a short message of what to do, not long details, so record longer thoughts separately, without starting them that way."
        )
    } else {
        Body("The Notebook keeps notes and reminders you type.")
    }
    Body(
        "The next pages ask how the Notebook should work for you. Go through them when you have time to read and decide, " +
            "since your choices shape how it works. You can change them later in Settings."
    )
    Body("Moving from another phone? Restore your Notebook from a backup file first, then continue here.")
}

@Composable
private fun SpeechPage(viewModel: NotebookSetupViewModel) {
    val speech by viewModel.speech.collectAsState()
    val offer by viewModel.speechOffer.collectAsState()
    val failed by viewModel.speechStartFailed.collectAsState()
    val offerFailed by viewModel.speechOfferFailed.collectAsState()
    val download by viewModel.speechDownload.collectAsState()
    LaunchedEffect(viewModel) { viewModel.recheckSpeech() }
    PageTitle("Speech recognition")
    when (speech) {
        null -> CircularProgressIndicator(Modifier.size(24.dp))
        SpeechSetup.READY -> Body("Speech recognition is ready for the Notebook.")
        SpeechSetup.DOWNLOADING -> {
            val slug = (download as? ModelDownloadStatus.Downloading)?.modelSlug
            val name = slug?.let { ModelCatalog.byId(it)?.displayName ?: it } ?: offer?.name ?: "the speech model"
            Body("Downloading $name in the background, or waiting for a network. You can go on.")
        }
        SpeechSetup.REBBLE_ONLY -> Body(
            "Your speech recognition setting sends audio only to Rebble, which the Notebook can't use, so Notebook recordings " +
                "will wait. To use the Notebook, choose a setting that uses this phone, such as Local Only or one with Local Fallback, " +
                "or one with your own transcription server, in Settings > AI > Speech Recognition."
        )
        SpeechSetup.NEEDS_MODEL -> {
            Body("The Notebook needs a speech model on this phone to turn recordings into text.")
            val chosen = offer
            if (offerFailed) {
                Body("Gravel couldn't pick a speech model for this phone. You can choose one later in Settings > AI > Speech Recognition.")
            } else if (chosen == null) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                Body("Recommended for this phone: ${chosen.name}, ${chosen.info.sizeInMB} MB. Watch dictation uses the same model when it runs on this phone.")
                Button(onClick = viewModel::downloadSpeech) { Text("Download") }
            }
            if (failed) Text("The download couldn't start. Try again.", color = MaterialTheme.colorScheme.error)
            (download as? ModelDownloadStatus.Failed)?.let { Text(it.errorMessage, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TextModelPage(setup: NotebookSetupViewModel) {
    val viewModel = koinViewModel<TextModelsViewModel>()
    LaunchedEffect(viewModel) { viewModel.start() }
    val rows by viewModel.rows.collectAsState()
    val fit by viewModel.fit.collectAsState()
    val facts by setup.textFacts.collectAsState()
    val request by setup.textRequest.collectAsState()
    var heavy by rememberSaveable { mutableStateOf<String?>(null) }
    val totalRam = fit?.totalRamBytes ?: 0L
    PageTitle("Text model")
    Body("A text model on this phone reads each transcript and turns it into a titled note or a reminder's message.")
    if (!textModelReady(facts, request)) Body("Download one to go on. It downloads in the background, or waits for a network.")
    if (rows.isEmpty()) {
        CircularProgressIndicator(Modifier.size(24.dp))
    } else {
        Column {
            rows.sortedByDescending { it.recommended }.forEach { row ->
                TextModelListItem(
                    row = row,
                    totalRamBytes = totalRam,
                    onDownload = { if (row.tooHeavy) heavy = row.model.id else setup.downloadText(row.model.id) },
                    onCancelDownload = viewModel::cancelDownload,
                    onChoose = { viewModel.choose(row.model.id) },
                    onDelete = null,
                )
            }
        }
    }
    heavy?.let(LlmModelCatalog::byId)?.let { model ->
        AlertDialog(
            onDismissRequest = { heavy = null },
            title = { Text("May run slowly") },
            text = { Text(heavyWarning(model, totalRam)) },
            confirmButton = {
                TextButton(onClick = {
                    heavy = null
                    setup.downloadText(model.id)
                }) { Text("Download anyway") }
            },
            dismissButton = { TextButton(onClick = { heavy = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TypedOnlyPage() {
    PageTitle("Typed notes on this phone")
    Body(
        "This phone can't run what the Notebook needs to turn recordings into text, so recording is off here. " +
            "You can still type notes and reminders, and back them up."
    )
}

@Composable
private fun ProcessingPage(tab: NotebookTab) {
    val settings by tab.settings.collectAsState()
    PageTitle("Long recordings")
    Body("These two settings decide when recordings over 30 seconds are turned into text.")
    SwitchRow("Long Recordings Wait for Power", NotebookSettingsText.WAIT_FOR_POWER, settings.longRecordingsWaitForPower, tab::setLongRecordingsWaitForPower)
    SwitchRow("Interrupt Long Recordings", NotebookSettingsText.INTERRUPT_LONG, settings.interruptLongRecordings, tab::setInterruptLongRecordings)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("$description.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QuickCompletionPage(tab: NotebookTab) {
    val settings by tab.settings.collectAsState()
    var dialog by rememberSaveable { mutableStateOf(false) }
    PageTitle("Quick reminder completion")
    Body("Start a recording with a short word to log that you finished something, for example \"done, gave the dog its pills.\"")
    Body("Nothing is marked done on its own: what you said after the word waits in the Notebook, and you pick the reminder it completes.")
    val word = settings.triggerWord
    if (word == null) {
        Body("Quick reminder completion is off.")
        OutlinedButton(onClick = { dialog = true }) { Text("Turn on") }
    } else {
        Body("On, with the trigger word \"$word\".")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialog = true }) { Text("Change word") }
            TextButton(onClick = tab::turnOffTriggerWord) { Text("Turn off") }
        }
    }
    if (dialog) tab.TriggerWordDialog(onDismiss = { dialog = false })
}

@Composable
private fun ReminderTimePage(tab: NotebookTab) {
    val settings by tab.settings.collectAsState()
    PageTitle("Default reminder time")
    Body("${NotebookSettingsText.QUICK_REMIND}.")
    Column {
        NotebookSettingsChoices.withCurrent(NotebookSettingsChoices.quickRemindMinutes, settings.quickRemindMinutes).forEach { minutes ->
            Row(
                Modifier.fillMaxWidth().clickable { tab.setQuickRemindMinutes(minutes) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = minutes == settings.quickRemindMinutes, onClick = { tab.setQuickRemindMinutes(minutes) })
                Text(NotebookSettingsChoices.minutesText(minutes))
            }
        }
    }
}

@Composable
private fun BackupsPage(tab: NotebookTab) {
    val backups by tab.autoBackup.collectAsState()
    PageTitle("Automatic backups")
    Body("${NotebookSettingsText.AUTO_BACKUPS}. Pick a folder that is copied off this phone, so a lost or reset phone doesn't take your Notebook with it.")
    when {
        !backups.known -> CircularProgressIndicator(Modifier.size(24.dp))
        backupsOn(backups) -> Body("Automatic backups are on.")
        backups.setUpAgain -> {
            Body("${NotebookSettingsText.SET_UP_AGAIN}.")
            OutlinedButton(onClick = tab::setUpAutoBackupAgain) { Text("Set up again") }
        }
        backups.unreadable -> Body("Gravel can't read its automatic backup settings right now. Check Settings > Notebook later.")
        backups.setUp -> {
            Body("Automatic backups are off.")
            OutlinedButton(onClick = { tab.setAutoBackupEnabled(true) }) { Text("Turn on") }
        }
        else -> OutlinedButton(onClick = tab::setUpAutoBackup) { Text("Set up") }
    }
    if (backups.known && !backupsOn(backups) && !(backups.unreadable && !backups.setUpAgain)) {
        Body("If you choose Not now, the Notebook tab reminds you again in a week.")
    }
}

@Composable
private fun DonePage(recording: Boolean) {
    PageTitle("You're set")
    Body(
        "Swipe-Away Snooze, how long reminder history is kept and the rest of the Notebook's settings are in Settings > Notebook. " +
            "To see your phone's calendar in the Notebook's Calendar, turn it on in Settings > Phone Calendar."
    )
    if (recording) Body("If Gravel doesn't have microphone access yet, the Notebook asks for it the first time you record.")
    Body("You can go through this guide again from Settings > Notebook.")
}
