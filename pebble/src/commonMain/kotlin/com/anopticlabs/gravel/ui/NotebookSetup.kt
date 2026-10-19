package com.anopticlabs.gravel.ui

import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelDownloadStatus

/**
 * The setup guide's pages in order. Names are saved to resume at, so a rename sends a guide in progress back to its
 * first page; the order decides where a saved page this phone skips resumes ([resumeStep]).
 */
enum class SetupStep { EARLY_BETA, WELCOME, SPEECH, TEXT_MODEL, TYPED_ONLY, PROCESSING, QUICK_COMPLETION, REMINDER_TIME, BACKUPS, DONE }

/** What the speech page offers under the app's speech setting. */
enum class SpeechSetup { READY, DOWNLOADING, NEEDS_MODEL, REBBLE_ONLY }

/**
 * A download the guide scheduled: it counts as under way until its lane reports a failure other than [staleFailure],
 * the one the lane already held when it was scheduled.
 */
class DownloadRequest(val staleFailure: ModelDownloadStatus.Failed?) {
    fun failed(lane: ModelDownloadStatus): Boolean = lane is ModelDownloadStatus.Failed && lane !== staleFailure
}

/** [download] is the speech lane's, Downloading while a job runs or is pending at process start. Rebble Only stays out of reach whatever downloads. */
fun speechSetup(canTranscribe: Boolean, mode: CactusSTTMode, download: ModelDownloadStatus, request: DownloadRequest?): SpeechSetup = when {
    canTranscribe -> SpeechSetup.READY
    mode == CactusSTTMode.RebbleOnly -> SpeechSetup.REBBLE_ONLY
    download is ModelDownloadStatus.Downloading -> SpeechSetup.DOWNLOADING
    request != null && !request.failed(download) -> SpeechSetup.DOWNLOADING
    else -> SpeechSetup.NEEDS_MODEL
}

/** The speech page lets the guide go on unless it still needs a model, or when no model could be offered. */
fun speechCanGoOn(speech: SpeechSetup?, offerFailed: Boolean): Boolean =
    speech != null && (speech != SpeechSetup.NEEDS_MODEL || offerFailed)

/** The pages for this phone: one typed-only page replaces the recording pages; [speechPage] while the Notebook cannot transcribe. */
fun setupSteps(recordingSupported: Boolean, speechPage: Boolean): List<SetupStep> = if (!recordingSupported) {
    listOf(SetupStep.EARLY_BETA, SetupStep.WELCOME, SetupStep.TYPED_ONLY, SetupStep.REMINDER_TIME, SetupStep.BACKUPS, SetupStep.DONE)
} else {
    listOfNotNull(
        SetupStep.EARLY_BETA,
        SetupStep.WELCOME,
        SetupStep.SPEECH.takeIf { speechPage },
        SetupStep.TEXT_MODEL,
        SetupStep.PROCESSING,
        SetupStep.QUICK_COMPLETION,
        SetupStep.REMINDER_TIME,
        SetupStep.BACKUPS,
        SetupStep.DONE,
    )
}

/**
 * [saved] when it is one of [steps], else the first of [steps] after it (a page this phone skips), else the first page;
 * but a page in [unmet] (a model page that would hold Next) before that one comes first.
 */
fun resumeStep(saved: String?, steps: List<SetupStep>, unmet: Set<SetupStep> = emptySet()): SetupStep {
    val step = SetupStep.entries.firstOrNull { it.name == saved } ?: return steps.first()
    val target = steps.firstOrNull { it.ordinal >= step.ordinal } ?: steps.last()
    return steps.firstOrNull { it in unmet && it.ordinal < target.ordinal } ?: target
}

/** A text model is on the phone, downloading, or scheduled by [request] and not failed since. */
fun textModelReady(facts: TextModelFacts, request: DownloadRequest? = null): Boolean = facts.read &&
    (facts.installed.isNotEmpty() || facts.download is ModelDownloadStatus.Downloading || (request != null && !request.failed(facts.download)))

/** Whether Next is offered: the speech page per [speechCanGoOn], the text page per [textModelReady], backups once they are on (Not now is separate). */
fun canContinue(step: SetupStep, speechGoOn: Boolean, textReady: Boolean, backupsOn: Boolean): Boolean = when (step) {
    SetupStep.SPEECH -> speechGoOn
    SetupStep.TEXT_MODEL -> textReady
    SetupStep.BACKUPS -> backupsOn
    else -> true
}

/** Automatic backups are running as set up: not off, never set up, unreadable, or waiting to be set up again. */
fun backupsOn(values: NotebookAutoBackupValues): Boolean =
    values.setUp && values.enabled && !values.setUpAgain && !values.unreadable

/** What the Notebook tab shows. */
enum class SetupSlot { NOTHING, GUIDE, NOTEBOOK }

/** Nothing until the guide's state is read, then the guide until it is finished or while it was opened again. */
fun setupSlot(state: NotebookSetupState): SetupSlot = when {
    !state.read -> SetupSlot.NOTHING
    !state.done || state.rerun -> SetupSlot.GUIDE
    else -> SetupSlot.NOTEBOOK
}
