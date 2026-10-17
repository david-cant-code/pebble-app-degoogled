package com.anopticlabs.gravel.notebook

import com.anopticlabs.gravel.notebook.storage.Cryptographer
import com.anopticlabs.gravel.notebook.storage.NotebookFiles
import com.anopticlabs.gravel.notebook.storage.NotebookKeystore
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.QueueStore
import com.anopticlabs.gravel.notebook.storage.ReminderEventStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The Notebook's stores; constructing them touches neither the Keystore nor a file. */
val notebookModule = module {
    single<Cryptographer> { NotebookFiles.crypto(NotebookKeystore(), NotebookFiles.root(androidContext())) }
    single { NoteStore(NotebookFiles.root(androidContext()), get()) }
    single { QueueStore(NotebookFiles.root(androidContext()), get()) }
    single { ReminderStore(NotebookFiles.root(androidContext()), get()) }
    single { ReminderEventStore(NotebookFiles.root(androidContext()), get()) }
    single { VoiceCompletionStore(NotebookFiles.root(androidContext()), get()) }
}
