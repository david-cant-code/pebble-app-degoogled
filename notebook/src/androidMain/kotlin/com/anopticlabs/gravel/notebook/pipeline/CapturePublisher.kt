package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.notes.noteWriteFolder
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.reminders.cutToReminderMessage
import com.anopticlabs.gravel.notebook.reminders.mergeParkedForDisplay
import com.anopticlabs.gravel.notebook.reminders.planReminderArm
import com.anopticlabs.gravel.notebook.reminders.shouldPostVoiceCompletionNotification
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What publishing a capture wrote; the drain settles the capture's row from it. */
sealed interface PublishedCapture {
    data class Parked(val completionId: String) : PublishedCapture

    data class ReminderSet(val reminder: ReminderItem, val scheduled: Boolean, val defaulted: Boolean) : PublishedCapture

    data class NoteSaved(val noteId: String, val title: String, val classificationFailed: Boolean) : PublishedCapture
}

/**
 * Publishes a capture by storing what it asked for (a parked claim, a reminder or a note);
 * a failure of that write propagates, so the capture stays queued. What follows the write (the
 * alarm, a notification, the refresh tick) is best effort and never fails the publish.
 */
class CapturePublisher(
    private val notes: NoteStore,
    private val reminders: ReminderStore,
    private val completions: VoiceCompletionStore,
    private val alarms: ReminderAlarms,
    private val notifier: ReminderNotifier,
    private val refresh: ReminderRefreshSignal,
    private val quickRemindMinutes: () -> Int,
    private val appBackgrounded: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // Orders a park's save and notice against readParked's read and cancel.
    private val completionNotice = Mutex()

    /** Strict reads of every store file a publish writes, so one that cannot be used is found before any engine runs. */
    fun checkStores() {
        notes.readMetadata(strict = true)
        notes.readFolders(strict = true)
        notes.readFolderTags(strict = true)
        reminders.readAll(strict = true)
        completions.readAll(strict = true)
    }

    /** [offeredFolders] are the folders the note was routed among; see [noteWriteFolder]. */
    suspend fun publish(built: BuiltCapture, capturedAtMillis: Long, offeredFolders: List<String>): PublishedCapture =
        when (built) {
            is BuiltCapture.Completion -> park(built.utterance, capturedAtMillis)
            is BuiltCapture.Reminder -> reminder(built.proposal)
            is BuiltCapture.Note -> note(built.note, offeredFolders)
        }

    private suspend fun park(utterance: String, capturedAtMillis: Long): PublishedCapture {
        val completion = VoiceCompletion(newId(), utterance, capturedAtMillis)
        completionNotice.withLock {
            completions.save(completion)
            bestEffort("refresh") { refresh.emit() }
            bestEffort("completion notice") {
                val count = mergeParkedForDisplay(completions.readAll(), emptyList(), completion).size
                if (shouldPostVoiceCompletionNotification(appBackgrounded(), count)) notifier.postCompletionsToReview(count)
            }
        }
        return PublishedCapture.Parked(completion.completionId)
    }

    /**
     * The parked claims, read strictly; with none left, or with [reviewing] (the user has the
     * claims in front of them), the "to review" notification is canceled.
     */
    suspend fun readParked(reviewing: Boolean = false): List<VoiceCompletion> = completionNotice.withLock {
        completions.readAll(strict = true).also {
            if (it.isEmpty() || reviewing) {
                currentCoroutineContext().ensureActive()
                notifier.cancelCompletionsToReview()
            }
        }
    }

    private suspend fun reminder(proposal: ReminderProposal): PublishedCapture {
        val message = cutToReminderMessage(proposal.message)
        val plan = planReminderArm(proposal, quickRemindMinutes(), now())
        val item = reminders.create { id -> ReminderItem(id, plan.millis, message, timeToCheck = plan.toCheck) }
        // A reminder stored but not armed waits for the next re-arm sweep and gets no set notice.
        val scheduled = bestEffort("alarm for reminder ${item.id}") { alarms.arm(item) }
        // Before this refresh: a save or Keep from the row that came ahead of the post would leave the notice up.
        if (scheduled) bestEffort("set notice") { notifier.postSetNotice(item) }
        bestEffort("refresh") { refresh.emit() }
        return PublishedCapture.ReminderSet(item, scheduled, plan.defaulted)
    }

    private suspend fun note(note: BuiltNote, offeredFolders: List<String>): PublishedCapture {
        val folder = noteWriteFolder(note.folder, offeredFolders)
        val noteId = notes.createListedNote(folder, note.filename, note.markdown, title = note.title, date = note.date).id
        return PublishedCapture.NoteSaved(noteId, note.title, note.classificationFailed)
    }

    private inline fun bestEffort(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "$what failed after the capture was published: ${e::class.simpleName}" }
        false
    }

    private companion object {
        val logger = Logger.withTag("CapturePublisher")
    }
}
