package com.anopticlabs.gravel.notebook

import com.anopticlabs.gravel.notebook.alarms.AndroidReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.AndroidReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarmScheduler
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.alarms.ReminderPresence
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings
import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.NotebookFiles
import com.anopticlabs.gravel.notebook.storage.NotebookKeystore
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The Notebook's stores and alarm stack; constructing them touches neither the Keystore nor a file. */
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
    single { NotebookNavigator() }
}
