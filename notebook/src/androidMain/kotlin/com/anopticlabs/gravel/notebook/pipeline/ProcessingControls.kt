package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.capture.ProcessingPause
import com.anopticlabs.gravel.notebook.capture.ProcessingRules
import com.anopticlabs.gravel.notebook.capture.QueuedCapture
import com.anopticlabs.gravel.notebook.capture.isLong
import com.anopticlabs.gravel.notebook.capture.mayStart
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's pause, in a file under the no-backup directory: it survives a restart and stays on this phone. */
class ProcessingPauseFile(private val dir: File) {
    private val file get() = File(dir, FILE_NAME)

    fun read(): ProcessingPause {
        if (!file.exists()) return ProcessingPause.NONE
        return try {
            ProcessingPause.valueOf(file.readText().trim())
        } catch (e: Exception) {
            logger.w { "processing pause unreadable (${e::class.simpleName}); none" }
            ProcessingPause.NONE
        }
    }

    /** False when the pause could not be saved; it then lasts only as long as the process. */
    fun write(pause: ProcessingPause): Boolean = try {
        val staging = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(staging).use { out ->
            out.write(pause.name.toByteArray())
            out.fd.sync()
        }
        if (!staging.renameTo(file)) throw IOException("rename failed")
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (e: Exception) {
        logger.w { "processing pause not saved (${e::class.simpleName})" }
        false
    }

    private companion object {
        const val FILE_NAME = "notebook_processing_pause"
        val logger = Logger.withTag("ProcessingPauseFile")
    }
}

/**
 * The user's controls over capture processing: the two pauses, Process now, and the stop a short
 * capture gives a long one mid-transcription. [interruptLong] and [longWaitsForPower] are the
 * user's settings, [pluggedIn] the phone's power and [speech] the speech setting's route, each read at every use; [interrupt] is
 * [CaptureDrain.interrupt], and [runAgain] starts a run or makes the one under way go round
 * ([CaptureCoordinator.requestAgain]), since that run's pass may have read the queue too early.
 */
class ProcessingControls(
    private val pauseFile: ProcessingPauseFile,
    private val interruptLong: () -> Boolean,
    private val longWaitsForPower: () -> Boolean,
    private val pluggedIn: () -> Boolean,
    private val interrupt: (transcribingOnly: Boolean, which: (QueuedCapture) -> Boolean) -> Unit,
    private val runAgain: () -> Unit,
    private val speech: () -> SpeechRoute,
) {
    // Read on first use, so the first drain already honors a pause saved before a restart.
    private val _pause by lazy { MutableStateFlow(pauseFile.read()) }
    val pause: StateFlow<ProcessingPause> by lazy { _pause.asStateFlow() }

    // Makes each pause, its stop and its save one step, so actions from different threads cannot interleave.
    private val lock = Any()

    private val approved = ConcurrentHashMap.newKeySet<String>()

    fun rules(): ProcessingRules = ProcessingRules(_pause.value, longWaitsForPower(), pluggedIn(), approved.toSet(), speech())

    // Each pause is in place before its stop, so the drain's check before its next capture already sees it.
    fun pauseAll() {
        synchronized(lock) {
            set(ProcessingPause.ALL)
            interrupt(false) { !it.fromWatch }
        }
    }

    fun pauseLong() {
        synchronized(lock) {
            if (_pause.value == ProcessingPause.ALL) return
            set(ProcessingPause.LONG)
            interrupt(false) { it.isLong }
        }
    }

    fun resume() {
        synchronized(lock) { set(ProcessingPause.NONE) }
        runAgain()
    }

    /** Lets the long captures [ids] start on battery, for as long as the process lives. */
    fun processNow(ids: Collection<String>) {
        approved += ids
        runAgain()
    }

    /**
     * For a capture just queued: a short one that may start stops a long one still transcribing, when the user's
     * setting says so and the long one may start again at once (one running on battery under the charger rule finishes).
     */
    fun captureQueued(entry: QueuedCapture) {
        if (entry.isLong || !interruptLong() || !rules().mayStart(entry)) return
        interrupt(true) { it.isLong && rules().mayStart(it) }
    }

    /** Plugging in may let held captures start, and unplugging changes what waits for power. */
    fun onPowerChanged() {
        if (longWaitsForPower()) runAgain()
    }

    private fun set(pause: ProcessingPause) {
        _pause.value = pause
        pauseFile.write(pause)
    }
}
