package com.anopticlabs.gravel.notebook

import com.anopticlabs.gravel.notebook.alarms.AndroidReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.alarms.NotebookStartup
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarmScheduler
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.ReminderPresence
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
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.binds
import org.koin.dsl.module

/** The Notebook's stores, alarm stack, capture pipeline and sources; constructing them touches neither the Keystore nor a file. */
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
            startCharger = get<LongCaptureCharger>()::start,
            startNetworkWatch = get<ServerNetworkWatch>()::start, startSpeechWatch = get<SpeechSettingWatch>()::start,
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
    single { NotebookHousekeeping(get(), get()) }
    single { AndroidCaptureKeepAlive(androidContext()) } binds arrayOf(CaptureKeepAlive::class, RecordingKeepAlive::class)
    single { CaptureCoordinator(drain = get(), housekeeping = get(), rows = get(), keepAlive = get()) }
    single { CaptureIngest(get(), captureQueued = { get<CaptureCoordinator>().captureQueued() }, entryQueued = { get<ProcessingControls>().captureQueued(it) }) }
    single<Microphone> { AudioRecordMicrophone(androidContext()) }
    single { PhoneRecorder(microphone = get(), ingest = get(), keepAlive = get(), text = get(), coordinator = get()) }
    single { TypedNotes(get()) }
    single { NotebookNavigator() }
}
