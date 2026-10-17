package com.anopticlabs.gravel.ui

import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookSetupTest {
    private val downloading = ModelDownloadStatus.Downloading("whisper-base")

    private val failed = ModelDownloadStatus.Failed("whisper-base", "no network")

    @Test
    fun aNotebookThatCanTranscribeNeedsNoSpeechPageWhateverTheSetting() {
        assertEquals(SpeechSetup.READY, speechSetup(true, CactusSTTMode.LocalOnly, ModelDownloadStatus.Idle, null))
        assertEquals(SpeechSetup.READY, speechSetup(true, CactusSTTMode.RebbleFallback, downloading, DownloadRequest(null)))
    }

    @Test
    fun rebbleOnlyIsNamedEvenWhileASpeechModelDownloads() {
        assertEquals(SpeechSetup.REBBLE_ONLY, speechSetup(false, CactusSTTMode.RebbleOnly, downloading, DownloadRequest(null)))
        assertEquals(SpeechSetup.REBBLE_ONLY, speechSetup(false, CactusSTTMode.RebbleOnly, ModelDownloadStatus.Idle, null))
    }

    @Test
    fun aRunningSpeechDownloadCountsAsUnderWay() {
        assertEquals(SpeechSetup.DOWNLOADING, speechSetup(false, CactusSTTMode.LocalOnly, downloading, null))
        assertEquals(SpeechSetup.NEEDS_MODEL, speechSetup(false, CactusSTTMode.LocalOnly, ModelDownloadStatus.Idle, null))
        assertEquals(SpeechSetup.NEEDS_MODEL, speechSetup(false, CactusSTTMode.RemoteOnly, failed, null))
    }

    @Test
    fun aScheduledSpeechDownloadCountsAsUnderWayUntilItFails() {
        assertEquals(SpeechSetup.DOWNLOADING, speechSetup(false, CactusSTTMode.LocalOnly, ModelDownloadStatus.Idle, DownloadRequest(null)))
        assertEquals(SpeechSetup.NEEDS_MODEL, speechSetup(false, CactusSTTMode.LocalOnly, failed, DownloadRequest(null)))
    }

    @Test
    fun aFailureTheLaneHeldBeforeTheRequestDoesNotEndIt() {
        val stale = ModelDownloadStatus.Failed("whisper-base", "Download failed")
        val again = ModelDownloadStatus.Failed("whisper-base", "Download failed")
        assertEquals(SpeechSetup.DOWNLOADING, speechSetup(false, CactusSTTMode.LocalOnly, stale, DownloadRequest(stale)))
        assertEquals(SpeechSetup.NEEDS_MODEL, speechSetup(false, CactusSTTMode.LocalOnly, again, DownloadRequest(stale)))
    }

    @Test
    fun aScheduledTextDownloadCountsUntilANewFailure() {
        val stale = ModelDownloadStatus.Failed("qwen", "Download could not be started")
        assertTrue(textModelReady(TextModelFacts(read = true), DownloadRequest(null)))
        assertTrue(textModelReady(TextModelFacts(read = true, download = stale), DownloadRequest(stale)))
        assertFalse(textModelReady(TextModelFacts(read = true, download = ModelDownloadStatus.Failed("qwen", "x")), DownloadRequest(stale)))
        assertFalse(textModelReady(TextModelFacts(read = false), DownloadRequest(null)))
        assertFalse(textModelReady(TextModelFacts(read = true)))
    }

    @Test
    fun theSpeechPageGoesOnOnceUnderWayOrWhenNoModelCanBeOffered() {
        assertFalse(speechCanGoOn(null, offerFailed = true))
        assertFalse(speechCanGoOn(SpeechSetup.NEEDS_MODEL, offerFailed = false))
        assertTrue(speechCanGoOn(SpeechSetup.NEEDS_MODEL, offerFailed = true))
        listOf(SpeechSetup.READY, SpeechSetup.DOWNLOADING, SpeechSetup.REBBLE_ONLY).forEach { assertTrue(speechCanGoOn(it, offerFailed = false), it.name) }
    }

    @Test
    fun aPhoneThatCanRecordGetsEveryPageAndTheSpeechPageOnlyWhenAsked() {
        assertEquals(
            listOf(
                SetupStep.EARLY_BETA, SetupStep.WELCOME, SetupStep.SPEECH, SetupStep.TEXT_MODEL, SetupStep.PROCESSING,
                SetupStep.QUICK_COMPLETION, SetupStep.REMINDER_TIME, SetupStep.BACKUPS, SetupStep.DONE,
            ),
            setupSteps(recordingSupported = true, speechPage = true),
        )
        assertFalse(SetupStep.SPEECH in setupSteps(recordingSupported = true, speechPage = false))
        assertFalse(SetupStep.TYPED_ONLY in setupSteps(recordingSupported = true, speechPage = true))
    }

    @Test
    fun aTypedOnlyPhoneSkipsEveryRecordingPage() {
        assertEquals(
            listOf(SetupStep.EARLY_BETA, SetupStep.WELCOME, SetupStep.TYPED_ONLY, SetupStep.REMINDER_TIME, SetupStep.BACKUPS, SetupStep.DONE),
            setupSteps(recordingSupported = false, speechPage = true),
        )
    }

    @Test
    fun theGuideResumesAtTheSavedPage() {
        val steps = setupSteps(recordingSupported = true, speechPage = true)
        assertEquals(SetupStep.BACKUPS, resumeStep("BACKUPS", steps))
        assertEquals(SetupStep.SPEECH, resumeStep("SPEECH", steps))
    }

    @Test
    fun aSavedPageThePhoneNowSkipsResumesAtTheNextOne() {
        assertEquals(SetupStep.TEXT_MODEL, resumeStep("SPEECH", setupSteps(recordingSupported = true, speechPage = false)))
        assertEquals(SetupStep.REMINDER_TIME, resumeStep("QUICK_COMPLETION", setupSteps(recordingSupported = false, speechPage = false)))
    }

    @Test
    fun theWatchPageComesBeforeTheLastOnPhonesThatRecordWhileThereIsAWatchAppToInstall() {
        val steps = setupSteps(recordingSupported = true, speechPage = false, watchPage = true)
        assertEquals(listOf(SetupStep.BACKUPS, SetupStep.WATCH, SetupStep.DONE), steps.takeLast(3))
        assertFalse(SetupStep.WATCH in setupSteps(recordingSupported = true, speechPage = false))
        assertFalse(SetupStep.WATCH in setupSteps(recordingSupported = false, speechPage = false, watchPage = true))
        assertEquals(SetupStep.DONE, resumeStep("WATCH", setupSteps(recordingSupported = true, speechPage = false)))
        assertEquals(SetupStep.WATCH, resumeStep("WATCH", steps))
        assertTrue(canContinue(SetupStep.WATCH, speechGoOn = false, textReady = false, backupsOn = false))
    }

    @Test
    fun aModelPageNoLongerSatisfiedComesBeforeTheSavedPage() {
        val steps = setupSteps(recordingSupported = true, speechPage = true)
        assertEquals(SetupStep.SPEECH, resumeStep("BACKUPS", steps, unmet = setOf(SetupStep.SPEECH, SetupStep.TEXT_MODEL)))
        assertEquals(SetupStep.TEXT_MODEL, resumeStep("REMINDER_TIME", steps, unmet = setOf(SetupStep.TEXT_MODEL)))
        assertEquals(SetupStep.SPEECH, resumeStep("SPEECH", steps, unmet = setOf(SetupStep.SPEECH)))
        assertEquals(SetupStep.EARLY_BETA, resumeStep(null, steps, unmet = setOf(SetupStep.SPEECH)))
    }

    @Test
    fun aTypedOnlyPageSavedOnAPhoneThatCanRecordGoesBackToAnUnmetModelPage() {
        val steps = setupSteps(recordingSupported = true, speechPage = false)
        assertEquals(SetupStep.TEXT_MODEL, resumeStep("TYPED_ONLY", steps, unmet = setOf(SetupStep.TEXT_MODEL)))
        assertEquals(SetupStep.PROCESSING, resumeStep("TYPED_ONLY", steps))
    }

    @Test
    fun nothingSavedOrAnUnknownNameStartsAtTheEarlyBetaPage() {
        val steps = setupSteps(recordingSupported = true, speechPage = false)
        assertEquals(SetupStep.EARLY_BETA, resumeStep(null, steps))
        assertEquals(SetupStep.EARLY_BETA, resumeStep("RENAMED", steps))
        assertEquals(SetupStep.EARLY_BETA, resumeStep(null, setupSteps(recordingSupported = false, speechPage = false)))
    }

    @Test
    fun aTextModelCountsOnceInstalledOrDownloadingAndNotBeforeThePhoneIsRead() {
        assertTrue(textModelReady(TextModelFacts(read = true, installed = setOf("qwen"))))
        assertTrue(textModelReady(TextModelFacts(read = true, download = ModelDownloadStatus.Downloading("qwen"))))
        assertFalse(textModelReady(TextModelFacts(read = true, download = ModelDownloadStatus.Failed("qwen", "no network"))))
        assertFalse(textModelReady(TextModelFacts(read = false, installed = setOf("qwen"))))
    }

    @Test
    fun nextWaitsOnTheModelPagesAndBackupsAndNowhereElse() {
        assertFalse(canContinue(SetupStep.SPEECH, speechGoOn = false, textReady = true, backupsOn = true))
        assertTrue(canContinue(SetupStep.SPEECH, speechGoOn = true, textReady = false, backupsOn = false))
        assertFalse(canContinue(SetupStep.TEXT_MODEL, speechGoOn = true, textReady = false, backupsOn = true))
        assertTrue(canContinue(SetupStep.TEXT_MODEL, speechGoOn = false, textReady = true, backupsOn = false))
        assertFalse(canContinue(SetupStep.BACKUPS, speechGoOn = true, textReady = true, backupsOn = false))
        assertTrue(canContinue(SetupStep.BACKUPS, speechGoOn = false, textReady = false, backupsOn = true))
        val free = SetupStep.entries - setOf(SetupStep.SPEECH, SetupStep.TEXT_MODEL, SetupStep.BACKUPS)
        free.forEach { assertTrue(canContinue(it, speechGoOn = false, textReady = false, backupsOn = false), it.name) }
    }

    @Test
    fun theTabShowsNothingUntilReadThenTheGuideUntilFinishedOrReopened() {
        assertEquals(SetupSlot.NOTHING, setupSlot(NotebookSetupState()))
        assertEquals(SetupSlot.NOTHING, setupSlot(NotebookSetupState(done = true)))
        assertEquals(SetupSlot.GUIDE, setupSlot(NotebookSetupState(read = true)))
        assertEquals(SetupSlot.GUIDE, setupSlot(NotebookSetupState(read = true, done = true, rerun = true)))
        assertEquals(SetupSlot.NOTEBOOK, setupSlot(NotebookSetupState(read = true, done = true)))
    }

    @Test
    fun backupsAreOnOnlyWhenSetUpSwitchedOnAndReadable() {
        assertTrue(backupsOn(NotebookAutoBackupValues(known = true, setUp = true, enabled = true)))
        assertFalse(backupsOn(NotebookAutoBackupValues(known = true, setUp = true, enabled = false)))
        assertFalse(backupsOn(NotebookAutoBackupValues(known = true, setUp = false, enabled = false)))
        assertFalse(backupsOn(NotebookAutoBackupValues(known = true, setUp = true, enabled = true, setUpAgain = true)))
        assertFalse(backupsOn(NotebookAutoBackupValues(known = true, setUp = true, enabled = true, unreadable = true)))
    }
}
