package com.anopticlabs.gravel.notebook

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.alarms.NotebookStartup
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarmScheduler
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.ReminderPresence
import android.net.Uri
import com.anopticlabs.gravel.notebook.backup.AndroidAutoBackupNotices
import com.anopticlabs.gravel.notebook.backup.AndroidCheckAlarm
import com.anopticlabs.gravel.notebook.backup.AutoBackupChecks
import com.anopticlabs.gravel.notebook.backup.AutoBackupNotices
import com.anopticlabs.gravel.notebook.backup.AutoBackupRunNote
import com.anopticlabs.gravel.notebook.backup.AutoBackupRunner
import com.anopticlabs.gravel.notebook.backup.AutoBackupScheduler
import com.anopticlabs.gravel.notebook.backup.AutoBackupSetup
import com.anopticlabs.gravel.notebook.backup.AutoBackupStore
import com.anopticlabs.gravel.notebook.backup.AutoBackups
import com.anopticlabs.gravel.notebook.backup.AutomaticRestore
import com.anopticlabs.gravel.notebook.backup.BackupDocuments
import com.anopticlabs.gravel.notebook.backup.BackupFlow
import com.anopticlabs.gravel.notebook.backup.BackupFolderDocuments
import com.anopticlabs.gravel.notebook.backup.BackupGate
import com.anopticlabs.gravel.notebook.backup.PickedFolders
import com.anopticlabs.gravel.notebook.backup.RecoveryKeyCheck
import com.anopticlabs.gravel.notebook.backup.WorkAutoBackupScheduler
import com.anopticlabs.gravel.notebook.backup.BackupStaging
import com.anopticlabs.gravel.notebook.backup.FileRunRecord
import com.anopticlabs.gravel.notebook.backup.NotebookBackup
import com.anopticlabs.gravel.notebook.pipeline.CaptureAudioDecoder
import com.anopticlabs.gravel.notebook.pipeline.AndroidCaptureKeepAlive
import com.anopticlabs.gravel.notebook.pipeline.CaptureBackend
import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.notebook.pipeline.CaptureIngest
import com.anopticlabs.gravel.notebook.pipeline.CaptureDrain
import com.anopticlabs.gravel.notebook.pipeline.CaptureKeepAlive
import com.anopticlabs.gravel.notebook.pipeline.CapturePublisher
import com.anopticlabs.gravel.notebook.pipeline.CaptureRows
import com.anopticlabs.gravel.notebook.pipeline.FailedCaptureActions
import com.anopticlabs.gravel.notebook.pipeline.LongCaptureCharger
import com.anopticlabs.gravel.notebook.pipeline.MediaCodecAudioDecoder
import com.anopticlabs.gravel.notebook.pipeline.OnDeviceCaptureBackend
import com.anopticlabs.gravel.notebook.pipeline.ProcessingControls
import com.anopticlabs.gravel.notebook.pipeline.ProcessingPauseFile
import com.anopticlabs.gravel.notebook.pipeline.RecordingKeepAlive
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerChargingWork
import com.anopticlabs.gravel.notebook.pipeline.CaptureSpeech
import com.anopticlabs.gravel.notebook.pipeline.ServerCheck
import com.anopticlabs.gravel.notebook.pipeline.ServerNetworkWatch
import com.anopticlabs.gravel.notebook.pipeline.SpeechSettingWatch
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerNetworkWork
import com.anopticlabs.gravel.notebook.pipeline.WorkManagerServerTryWork
import com.anopticlabs.gravel.network.UnmeteredWifi
import com.anopticlabs.gravel.notebook.pipeline.isPluggedIn
import com.anopticlabs.gravel.notebook.reminders.AndroidPhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.PhoneCalendar
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.sources.AudioRecordMicrophone
import com.anopticlabs.gravel.notebook.sources.Microphone
import com.anopticlabs.gravel.notebook.sources.PhoneRecorder
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.NotebookFiles
import com.anopticlabs.gravel.notebook.storage.NotebookKeystore
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.NotebookHousekeeping
import com.anopticlabs.gravel.notebook.storage.NotebookRecovery
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import com.anopticlabs.gravel.notebook.ui.hasScreenLock
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import com.anopticlabs.gravel.notebook.ui.NotebookStatusReader
import com.anopticlabs.gravel.notebook.ui.NotebookViewModel
import com.anopticlabs.gravel.notebook.ui.NotesViewModel
import com.anopticlabs.gravel.notebook.ui.RemindersViewModel
import com.anopticlabs.gravel.notebook.ui.StorageRecoveryRunner
import org.koin.android.ext.koin.androidContext
import java.io.File
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

/** The Notebook's stores, alarm stack, backups, capture pipeline, sources and screen; constructing them touches neither the Keystore nor a file. */
val notebookModule = module {
    single<Cryptographer> { NotebookFiles.crypto(NotebookKeystore(), NotebookFiles.root(androidContext())) }
    single { NoteStore(NotebookFiles.root(androidContext()), get()) }
    single { QueueStore(NotebookFiles.root(androidContext()), get()) }
    single { ReminderStore(NotebookFiles.root(androidContext()), get()) }
    single { ReminderEventStore(NotebookFiles.root(androidContext()), get()) }
    single { VoiceCompletionStore(NotebookFiles.root(androidContext()), get()) }
    single { NotebookPreferences(androidContext()) }
    single<ReminderSettings> { get<NotebookPreferences>() }
    single { ReminderRefreshSignal() }
    single<ReminderAlarmScheduler> { AndroidReminderAlarms(androidContext()) }
    single { NotebookLinkToken(androidContext().noBackupFilesDir) }
    single<ReminderNotifier> { AndroidReminderNotifier(androidContext()) { get<NotebookLinkToken>().value } }
    single { ReminderAlarms(get(), get(), get(), get(), get(), get()) }
    single { ReminderPresence(get(), get()) }
    single {
        NotebookStartup(
            get(), get(), get(), get(), get(),
            startBackups = get<AutoBackups<Uri>>()::start, checkBackups = get<AutoBackupChecks>()::request, startCharger = get<LongCaptureCharger>()::start,
            startNetworkWatch = get<ServerNetworkWatch>()::start, startSpeechWatch = get<SpeechSettingWatch>()::start,
        )
    }
    single { BackupStaging(NotebookFiles.root(androidContext())) }
    single { NotebookBackup(get(), get(), get(), get(), get(), get(), get()) }
    single { BackupGate() }
    single {
        BackupFlow(
            get<NotebookBackup>(), BackupDocuments(androidContext().contentResolver),
            runs = FileRunRecord(File(androidContext().noBackupFilesDir, "notebook_backup_run"), { it.toString() }, Uri::parse),
            requestDrain = { get<CaptureCoordinator>().captureQueued() },
            gate = get(),
            canTakeKey = { get<AutoBackupStore>().canAdoptKey() },
            takeKey = { get<AutoBackupSetup<Uri>>().startWithKey(it) },
            prepareRestore = { get<NotebookRecovery>().prepare(it) },
            automaticBackup = {
                get<AutoBackupStore>().newestRestorable()?.let { (entry, key) -> AutomaticRestore(Uri.parse(entry.uri), entry.name, key.chars, entry.sha256) }
            },
            screenLocked = { hasScreenLock(androidContext()) },
        )
    }
    single {
        NotebookRecovery(
            NotebookFiles.root(androidContext()), get(), get(), get(), get(), get(),
            resetKey = { NotebookKeystore().resetKey(it) },
            keyAbsent = { NotebookKeystore().existingKey() == null },
            afterReset = { startedOver ->
                runCatching { get<ReminderAlarms>().rearmAll() }.onFailure { e -> Logger.withTag("NotebookRecovery").w { "re-arm failed: ${e::class.simpleName}" } }
                get<ReminderRefreshSignal>().emit()
                if (startedOver) get<AutoBackups<Uri>>().afterFilesSetAside() else get<AutoBackups<Uri>>().refresh()
                get<CaptureCoordinator>().captureQueued()
            },
        )
    }
    single {
        StorageRecoveryRunner(
            get(),
            restoreFile = { get<BackupFlow<Uri>>().startRestore(it) },
            restoreAutomatic = { get<BackupFlow<Uri>>().startAutomaticRestore(it) },
        )
    }
    single { AutoBackupStore(NotebookFiles.root(androidContext()), get()) }
    single { BackupFolderDocuments(androidContext().contentResolver) }
    single<AutoBackupScheduler> { WorkAutoBackupScheduler(androidContext()) }
    single<AutoBackupNotices> { AndroidAutoBackupNotices(androidContext()) { get<NotebookLinkToken>().value } }
    single {
        val notes = get<NoteStore>()
        val reminders = get<ReminderStore>()
        val events = get<ReminderEventStore>()
        AutoBackupRunner<Uri>(
            get(), get(), get<BackupFolderDocuments>(), AutoBackupRunNote(File(androidContext().noBackupFilesDir, "notebook_auto_backup_run")), get(), get(),
            changeCount = { notes.revision.value + reminders.revision.value + events.revision.value },
            followUp = { get<AutoBackupScheduler>().afterChange(it) },
        )
    }
    single {
        AutoBackups<Uri>(
            get(), get(), get(), get<BackupFolderDocuments>(), get(),
            changes = listOf(get<NoteStore>().revision, get<ReminderStore>().revision, get<ReminderEventStore>().revision),
            requestChecks = { get<AutoBackupChecks>().request() },
        )
    }
    single { AutoBackupChecks(get(), get(), get(), AndroidCheckAlarm(androidContext())) }
    single { RecoveryKeyCheck<Uri>(get(), get(), get(), get()) }
    single { PickedFolders(File(androidContext().noBackupFilesDir, "notebook_auto_backup_picked")) }
    single {
        AutoBackupSetup<Uri>(
            get(), get(), get<BackupFolderDocuments>(), get(), get(),
            screenLocked = { hasScreenLock(androidContext()) },
            setAsideFirst = { get<AutoBackups<Uri>>().setAsideForNewSetup() },
            refresh = {
                get<AutoBackups<Uri>>().refresh()
                get<AutoBackupChecks>().request()
            },
        )
    }
    single { AppVisibility() }
    single { CaptureRows() }
    single<CaptureAudioDecoder> { MediaCodecAudioDecoder() }
    // CaptureSpeech, CaptureText and CaptureMemory come from the app, which binds them to the engines.
    single<CaptureBackend> { OnDeviceCaptureBackend(get(), get(), get(), get()) }
    single {
        CapturePublisher(
            notes = get(), reminders = get(), completions = get(), alarms = get(), notifier = get(),
            refresh = get(), quickRemindMinutes = { get<NotebookPreferences>().quickRemindMinutes },
            appBackgrounded = { get<AppVisibility>().isBackgrounded },
        )
    }
    single {
        CaptureDrain(
            get(), get(), get(), get(), get(), settings = { get<NotebookPreferences>().captureSettings },
            rules = { get<ProcessingControls>().rules() }, notice = { get<CaptureSpeech>().notice() },
            serverTried = WorkManagerServerTryWork(androidContext())::schedule,
        )
    }
    single {
        ProcessingControls(
            ProcessingPauseFile(androidContext().noBackupFilesDir),
            interruptLong = { get<NotebookPreferences>().interruptLongCaptures },
            longWaitsForPower = { get<NotebookPreferences>().longCapturesWaitForPower },
            pluggedIn = { isPluggedIn(androidContext()) },
            interrupt = { transcribingOnly, which -> get<CaptureDrain>().interrupt(transcribingOnly, which) },
            runAgain = { get<CaptureCoordinator>().requestAgain() },
            speech = { get<CaptureSpeech>().route() },
        )
    }
    single { ServerCheck() }
    single {
        ServerNetworkWatch(
            get<UnmeteredWifi>().usable, get<CaptureDrain>().held, get<CaptureDrain>().passes,
            request = { get<CaptureCoordinator>().requestAgain() }, WorkManagerNetworkWork(androidContext()),
        )
    }
    single { FailedCaptureActions(get(), processing = { get<CaptureDrain>().isProcessing(it) }, requestAgain = { get<CaptureCoordinator>().requestAgain() }) }
    single { LongCaptureCharger(androidContext(), get<CaptureDrain>().passes, get<CaptureDrain>().waitingForPower, controls = { get() }, WorkManagerChargingWork(androidContext())) }
    single { NotebookHousekeeping(get(), get(), get(), removeStartOverLeftovers = { get<NotebookRecovery>().removeStartOverLeftovers() }) }
    single { AndroidCaptureKeepAlive(androidContext()) } binds arrayOf(CaptureKeepAlive::class, RecordingKeepAlive::class)
    single { CaptureCoordinator(drain = get(), housekeeping = get(), rows = get(), keepAlive = get()) }
    single { CaptureIngest(get(), captureQueued = { get<CaptureCoordinator>().captureQueued() }, entryQueued = { get<ProcessingControls>().captureQueued(it) }) }
    single<Microphone> { AudioRecordMicrophone(androidContext()) }
    single { PhoneRecorder(microphone = get(), ingest = get(), keepAlive = get(), text = get(), coordinator = get()) }
    single { TypedNotes(get()) }
    single { NotebookNavigator() }
    single<PhoneCalendar> { AndroidPhoneCalendar(androidContext()) }
    single {
        NotebookStatusReader(
            get(), get(), get(),
            automaticBackupAt = { get<AutoBackupStore>().newestBackupAt(get<BackupFolderDocuments>()::heldFolders) },
            rules = { get<ProcessingControls>().rules() },
        )
    }
    viewModel {
        NotebookViewModel(
            get(), get(), get(), get(), get(), get(), get<VoiceCompletionStore>().revision, get<CaptureCoordinator>()::request, get(), get(), get(),
            serverTryAgain = {
                get<ServerCheck>().tryAgain()
                get<CaptureCoordinator>().requestAgain()
            },
        )
    }
    viewModel { NotesViewModel(get()) }
    viewModel {
        RemindersViewModel(
            reminders = get(), events = get(), alarms = get(), typedNotes = get(),
            publisher = get(), completions = get(), navigator = get(), refresh = get(), settings = get(),
            phoneCalendar = get(), phoneChoice = { get<NotebookPreferences>().phoneCalendar },
        )
    }
}
