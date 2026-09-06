package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.util.writeWavHeader
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeShortLe
import kotlin.time.Clock

/**
 * Debug-only archive of what the engine was fed. Watch dictation accuracy
 * varies between sessions in ways that only replaying the exact input
 * explains, so a debug build can keep the last few captures as plain WAV
 * files in the app's private storage. The writes are reached only through
 * the debug hooks and never happen in a release build; [clear] runs in
 * every build so a debug install's captures do not outlive it. Nothing is
 * ever uploaded, and a failure to write never touches the dictation that
 * produced the audio.
 */
internal object DictationCaptureDump {
    /** Files kept; the oldest beyond this are deleted after each write. */
    internal const val KEEP = 20

    private const val PREFIX = "dictation-"
    private const val SUFFIX = ".wav"
    /** Raw codec frames as the watch sent them, next to the decoded WAV. */
    private const val FRAMES_SUFFIX = ".spx"
    private val logger = Logger.withTag("DictationCaptureDump")

    /**
     * Writes [pcm16] (little-endian signed 16-bit mono at [sampleRate]) as
     * a WAV file and prunes the directory to [KEEP] files. Returns the
     * written path, or null when the platform has no capture directory or
     * the write failed (logged, never thrown).
     */
    fun write(pcm16: ByteArray, sampleRate: Int): String? =
        dictationCaptureDirectory()?.let { write(Path(it), pcm16, sampleRate) }

    internal fun write(directory: Path, pcm16: ByteArray, sampleRate: Int): String? =
        writeCapture(directory, SUFFIX, "audio") { sink ->
            sink.writeWavHeader(sampleRate, pcm16.size)
            sink.write(pcm16)
        }

    /**
     * Writes the codec [frames] of one dictation exactly as they arrived
     * from the watch, so a capture that decodes badly can be re-decoded
     * outside the app and the frame bytes themselves inspected. Layout is
     * [framesBytes]; pruning and failure handling mirror [write].
     */
    fun writeFrames(frames: List<ByteArray>): String? =
        dictationCaptureDirectory()?.let { writeFrames(Path(it), frames) }

    internal fun writeFrames(directory: Path, frames: List<ByteArray>): String? =
        writeCapture(directory, FRAMES_SUFFIX, "frame") { it.write(framesBytes(frames)) }

    /** Deletes every capture: the hook that wrote them is off, so none may stay behind. */
    fun clear() {
        dictationCaptureDirectory()?.let { clear(Path(it)) }
    }

    internal fun clear(directory: Path) {
        runCatching {
            if (SystemFileSystem.exists(directory)) {
                prune(directory, SUFFIX, keep = 0)
                prune(directory, FRAMES_SUFFIX, keep = 0)
            }
        }.onFailure { logger.w(it) { "Could not clear the dictation captures" } }
    }

    /**
     * One capture file: stamped with the current epoch millisecond under
     * [suffix], filled by [body], then the directory pruned to [KEEP] files
     * of that suffix. Returns the path, or null after a logged failure.
     */
    private fun writeCapture(directory: Path, suffix: String, label: String, body: (Sink) -> Unit): String? =
        runCatching {
            SystemFileSystem.createDirectories(directory)
            val file = Path(directory, "$PREFIX${Clock.System.now().toEpochMilliseconds()}$suffix")
            SystemFileSystem.sink(file).buffered().use(body)
            prune(directory, suffix)
            file.toString()
        }.onFailure { logger.w(it) { "Could not write the dictation $label capture" } }.getOrNull()

    private fun prune(directory: Path, suffix: String, keep: Int = KEEP) {
        val names = SystemFileSystem.list(directory).map { it.name }
        for (stale in captureNamesToPrune(names, keep, suffix)) {
            SystemFileSystem.delete(Path(directory, stale), mustExist = false)
        }
    }

    /**
     * Each frame as a little-endian 16-bit byte count followed by its
     * bytes, in arrival order; a frame longer than that field can carry is
     * truncated to it, which no watch codec frame approaches.
     */
    internal fun framesBytes(frames: List<ByteArray>): ByteArray {
        val out = kotlinx.io.Buffer()
        for (frame in frames) {
            val length = minOf(frame.size, 0xFFFF)
            out.writeShortLe(length.toShort())
            out.write(frame, 0, length)
        }
        return out.readByteArray()
    }

    /**
     * The capture file names to delete so that at most [keep] remain: the
     * epoch-millisecond stamp in the name orders them, so the lexically
     * smallest are the oldest. Non-capture names are left alone.
     */
    internal fun captureNamesToPrune(names: Collection<String>, keep: Int, suffix: String = SUFFIX): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(suffix) }
            .sorted()
            .dropLast(keep)
}

/** Directory for debug dictation captures, or null where none exists. */
internal expect fun dictationCaptureDirectory(): String?
