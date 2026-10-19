package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import coredevices.pebble.ui.TopBarParams
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookSetupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tab = FakeTab()
    private val text = FakeTextModels()
    private val speech = FakeSpeech()
    private val vm by lazy { NotebookSetupViewModel(tab, text, speech) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.started(saved: String? = null): NotebookSetupViewModel {
        tab.setupGuide.value = tab.setupGuide.value.copy(step = saved)
        vm.start()
        backgroundScope.launch(dispatcher) { vm.speech.collect {} }
        return vm
    }

    @Test
    fun theGuideResumesAtTheSavedPage() = runTest(dispatcher) {
        started("QUICK_COMPLETION")
        assertEquals(SetupStep.QUICK_COMPLETION, vm.step.value)
        assertFalse(SetupStep.SPEECH in vm.steps.value.orEmpty())
    }

    @Test
    fun aNotebookThatCannotTranscribeGoesBackToTheSpeechPage() = runTest(dispatcher) {
        tab.transcribes = false
        started("BACKUPS")
        assertEquals(SetupStep.SPEECH, vm.step.value)
    }

    @Test
    fun noTextModelGoesBackToTheTextModelPage() = runTest(dispatcher) {
        text.facts.value = TextModelFacts(read = true)
        started("BACKUPS")
        assertEquals(SetupStep.TEXT_MODEL, vm.step.value)
    }

    @Test
    fun aPhoneThatCannotRecordGetsTheTypedOnlyPages() = runTest(dispatcher) {
        tab.records = false
        text.facts.value = TextModelFacts(read = true)
        started("REMINDER_TIME")
        assertEquals(setupSteps(recordingSupported = false, speechPage = false), vm.steps.value)
        assertEquals(SetupStep.REMINDER_TIME, vm.step.value)
    }

    @Test
    fun eachMoveSavesItsPageAndASecondTapFromThePageLeftMovesNothing() = runTest(dispatcher) {
        started()
        vm.next(SetupStep.EARLY_BETA)
        vm.next(SetupStep.EARLY_BETA)
        assertEquals(SetupStep.WELCOME, vm.step.value)
        vm.back(SetupStep.WELCOME)
        assertEquals(SetupStep.EARLY_BETA, vm.step.value)
        assertEquals(listOf("WELCOME", "EARLY_BETA"), tab.saved)
    }

    @Test
    fun notNowOnBackupsSnoozesTheReminderAndGoesOnOnce() = runTest(dispatcher) {
        started("BACKUPS")
        vm.backupsNotNow(SetupStep.BACKUPS)
        vm.backupsNotNow(SetupStep.BACKUPS)
        assertEquals(1, tab.snoozes)
        assertEquals(SetupStep.DONE, vm.step.value)
    }

    @Test
    fun finishingForgetsTheRunSoTheNextOneStartsAtTheFirstPage() = runTest(dispatcher) {
        tab.transcribes = false
        started("BACKUPS")
        vm.downloadSpeech()
        assertEquals(SpeechSetup.DOWNLOADING, vm.speech.value)
        speech.schedules = false
        vm.downloadSpeech()
        assertTrue(vm.speechStartFailed.value)
        vm.downloadText("qwen")
        vm.finish()
        assertEquals(1, tab.finished)
        assertNull(vm.step.value)
        assertNull(vm.speechOffer.value)
        assertFalse(vm.speechStartFailed.value)
        assertNull(vm.textRequest.value)
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
        tab.setupGuide.value = tab.setupGuide.value.copy(rerun = true)
        vm.start()
        assertEquals(SetupStep.EARLY_BETA, vm.step.value)
    }

    @Test
    fun aRerunClosedFromOutsideEndsTheRun() = runTest(dispatcher) {
        tab.setupGuide.value = NotebookSetupState(read = true, done = true, rerun = true)
        started()
        vm.next(SetupStep.EARLY_BETA)
        assertEquals(SetupStep.WELCOME, vm.step.value)
        tab.setupGuide.value = tab.setupGuide.value.copy(rerun = false)
        assertNull(vm.step.value)
        tab.setupGuide.value = tab.setupGuide.value.copy(rerun = true)
        vm.start()
        assertEquals(SetupStep.EARLY_BETA, vm.step.value)
    }

    @Test
    fun comingBackToTheGuideKeepsThisRunsPageAndChecksTheModelsAgain() = runTest(dispatcher) {
        started()
        vm.next(SetupStep.EARLY_BETA)
        vm.next(SetupStep.WELCOME)
        vm.next(SetupStep.TEXT_MODEL)
        vm.leave()
        vm.start()
        assertEquals(SetupStep.PROCESSING, vm.step.value)
        text.facts.value = TextModelFacts(read = true)
        vm.leave()
        vm.start()
        assertEquals(SetupStep.TEXT_MODEL, vm.step.value)
    }

    @Test
    fun aSpeechPageThatLetsTheGuideGoOnIsNotReturnedTo() = runTest(dispatcher) {
        tab.transcribes = false
        speech.mode.value = CactusSTTMode.RebbleOnly
        started("BACKUPS")
        assertEquals(SetupStep.BACKUPS, vm.step.value)
        speech.mode.value = CactusSTTMode.LocalOnly
        speech.download.value = ModelDownloadStatus.Downloading("whisper-base")
        vm.leave()
        vm.start()
        assertEquals(SetupStep.BACKUPS, vm.step.value)
    }

    @Test
    fun aRetryOverAFailureLeftInTheLaneCountsAsUnderWay() = runTest(dispatcher) {
        tab.transcribes = false
        speech.download.value = ModelDownloadStatus.Failed("whisper-base", "Download failed")
        started()
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
        vm.downloadSpeech()
        assertEquals(SpeechSetup.DOWNLOADING, vm.speech.value)
        speech.download.value = ModelDownloadStatus.Downloading("whisper-base")
        speech.download.value = ModelDownloadStatus.Failed("whisper-base", "Download failed")
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
    }

    @Test
    fun aScheduledTextDownloadLetsTheTextPageGoOnWhileItWaits() = runTest(dispatcher) {
        text.facts.value = TextModelFacts(read = true)
        started()
        vm.downloadText("qwen")
        assertEquals(listOf("qwen"), text.downloads)
        assertTrue(textModelReady(vm.textFacts.value, vm.textRequest.value))
        text.facts.value = TextModelFacts(read = true, download = ModelDownloadStatus.Failed("qwen", "Download could not be started"))
        assertFalse(textModelReady(vm.textFacts.value, vm.textRequest.value))
    }

    @Test
    fun aScheduledSpeechDownloadLetsTheSpeechPageGoOn() = runTest(dispatcher) {
        tab.transcribes = false
        started()
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
        assertEquals(speech.offer, vm.speechOffer.value)
        vm.downloadSpeech()
        assertEquals(listOf(speech.offer), speech.downloads)
        assertEquals(SpeechSetup.DOWNLOADING, vm.speech.value)
        assertFalse(vm.speechStartFailed.value)
        speech.download.value = ModelDownloadStatus.Failed("whisper-base", "no network")
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
    }

    @Test
    fun aSpeechDownloadThatCannotBeScheduledSaysSoAndHoldsThePage() = runTest(dispatcher) {
        tab.transcribes = false
        speech.schedules = false
        started()
        vm.downloadSpeech()
        assertTrue(vm.speechStartFailed.value)
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
    }

    @Test
    fun noSpeechModelToOfferLetsTheGuideGoOn() = runTest(dispatcher) {
        tab.transcribes = false
        speech.offerError = IllegalStateException("no catalog entry")
        started()
        assertNull(vm.speechOffer.value)
        assertTrue(vm.speechOfferFailed.value)
        assertTrue(speechCanGoOn(vm.speech.value, vm.speechOfferFailed.value))
    }

    @Test
    fun theSpeechPageReadsTheSettingAgainWhenAskedTo() = runTest(dispatcher) {
        tab.transcribes = false
        started()
        assertEquals(SpeechSetup.NEEDS_MODEL, vm.speech.value)
        tab.transcribes = true
        vm.recheckSpeech()
        assertEquals(SpeechSetup.READY, vm.speech.value)
    }

    private class FakeSpeech : SetupSpeech {
        val offer = SpeechModelOffer(ModelInfo(slug = "whisper-base", sizeInMB = 142), "Whisper Base")
        var schedules = true
        var offerError: Exception? = null
        val downloads = mutableListOf<SpeechModelOffer>()
        override val mode = MutableStateFlow(CactusSTTMode.LocalOnly)
        override val download = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
        override suspend fun recommend(): SpeechModelOffer? = offerError?.let { throw it } ?: offer
        override suspend fun download(offer: SpeechModelOffer): Boolean {
            downloads += offer
            return schedules
        }
    }

    private class FakeTextModels : TextModels {
        val downloads = mutableListOf<String>()
        override val supported = true
        override val facts = MutableStateFlow(TextModelFacts(read = true, installed = setOf("qwen")))
        override fun refresh() = Unit
        override fun download(modelId: String) {
            downloads += modelId
        }
        override fun cancelDownload() = error("not used")
        override fun choose(modelId: String) = error("not used")
        override fun delete(modelId: String) = error("not used")
    }

    private class FakeTab : NotebookTab {
        var transcribes = true
        var records = true
        var snoozes = 0
        var finished = 0
        val saved = mutableListOf<String>()
        override val setupGuide = MutableStateFlow(NotebookSetupState(read = true))
        override fun saveSetupStep(step: String) {
            saved += step
        }
        override fun finishSetupGuide() {
            finished++
            setupGuide.value = setupGuide.value.copy(done = true, step = null, rerun = false)
        }
        override fun snoozeBackupReminder() {
            snoozes++
        }
        override suspend fun canTranscribe() = transcribes
        override suspend fun recordingSupported() = records
        override fun openSetupGuide() = error("not used")
        override fun closeSetupRerun() = error("not used")
        override val supported = true
        override val shown = MutableStateFlow(true)
        override fun setShown(shown: Boolean) = error("not used")
        @Composable override fun Screen(topBarParams: TopBarParams, openTextModels: () -> Unit, openSpeechSettings: () -> Unit) = Unit
        override val settings = MutableStateFlow(NotebookSettingsValues(null, 30, 10, 90, 8, false, emptySet(), true, true))
        override fun setQuickRemindMinutes(minutes: Int) = error("not used")
        override fun setSwipeSnoozeMinutes(minutes: Int) = error("not used")
        override fun setHistoryRetentionDays(days: Int) = error("not used")
        override fun setInterruptLongRecordings(on: Boolean) = error("not used")
        override fun setLongRecordingsWaitForPower(on: Boolean) = error("not used")
        override fun turnOffTriggerWord() = error("not used")
        @Composable override fun TriggerWordDialog(onDismiss: () -> Unit) = Unit
        override fun setPhoneCalendarShown(shown: Boolean) = error("not used")
        @Composable override fun PhoneCalendarsDialog(onDismiss: () -> Unit) = Unit
        override fun startBackup() = error("not used")
        override fun startRestore() = error("not used")
        override fun startBackupCheck() = error("not used")
        override val autoBackup = MutableStateFlow(NotebookAutoBackupValues())
        override fun setUpAutoBackup() = error("not used")
        override fun setUpAutoBackupAgain() = error("not used")
        override fun setAutoBackupEnabled(on: Boolean) = error("not used")
        override fun backUpNow() = error("not used")
        override fun changeBackupFolder() = error("not used")
        override fun checkRecoveryKey() = error("not used")
        override val keyLocation = MutableStateFlow<String?>(null)
        @Composable override fun BackupDialogs() = Unit
    }
}
