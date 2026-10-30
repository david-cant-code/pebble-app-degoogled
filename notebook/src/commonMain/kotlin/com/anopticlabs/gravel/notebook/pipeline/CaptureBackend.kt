package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.capture.CaptureStage
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** What a capture asked for, ready to publish. */
sealed interface BuiltCapture {
    data class Note(val note: BuiltNote) : BuiltCapture

    data class Reminder(val proposal: ReminderProposal) : BuiltCapture

    /** A completion claim: what was said after the trigger word, possibly nothing. */
    data class Completion(val utterance: String) : BuiltCapture
}

data class CaptureSettings(
    val triggerEnabled: Boolean = false,
    val triggerWord: String = "",
)

/**
 * One queued recording to process. [capturedAt] is when it was recorded: it dates the note and is
 * the "now" the reminder's time is read against. [folders] are the ones a note may be routed to.
 * [phoneTried], [markPhoneTried], [long], [fromWatch], [serverTries], [serverTriedAtMillis] and [markServerTry] are what
 * the entry gives its [SpeechCapture].
 */
class CaptureRequest(
    val audio: ByteArray,
    val capturedAt: Instant,
    val zone: TimeZone,
    val folders: List<String>,
    val folderTags: Map<String, List<String>>,
    val settings: CaptureSettings,
    val id: String = "",
    val phoneTried: Boolean = false,
    val markPhoneTried: suspend () -> Unit = {},
    val long: Boolean = false,
    val fromWatch: Boolean = false,
    val serverTries: Int = 0,
    val serverTriedAtMillis: Long? = null,
    val markServerTry: suspend (atMillis: Long) -> Boolean = { false },
)

/** Turns a recording into what it asked for; failures as [CaptureProcessingException]. */
interface CaptureBackend {
    /** Fails with [CaptureFailureKind.HOLD] while a capture could not be processed at all. */
    suspend fun checkReady()

    suspend fun process(request: CaptureRequest, onStage: (CaptureStage) -> Unit): BuiltCapture
}
