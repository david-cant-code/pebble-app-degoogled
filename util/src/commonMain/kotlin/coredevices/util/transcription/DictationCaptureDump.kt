package coredevices.util.transcription

import co.touchlab.kermit.Logger
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe
import kotlinx.io.writeString
import kotlin.time.Clock

/**
 * Debug-only archive of what the engine was fed. Watch dictation accuracy
 * varies between sessions in ways that only replaying the exact input
 * explains, so a debug build can keep the last few captures as plain WAV
 * files in the app's private storage. Nothing here runs in a release
 * build, nothing is ever uploaded, and a failure to write never touches
 * the dictation that produced the audio.
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
    fun write(pcm16: ByteArray, sampleRate: Int): String? {
        val dir = dictationCaptureDirectory() ?: return null
        return runCatching {
            val directory = Path(dir)
            SystemFileSystem.createDirectories(directory)
            val file = Path(directory, "$PREFIX${Clock.System.now().toEpochMilliseconds()}$SUFFIX")
            SystemFileSystem.sink(file).buffered().use { it.write(wavBytes(pcm16, sampleRate)) }
            prune(directory, SUFFIX)
            file.toString()
        }.onFailure { logger.w(it) { "Could not write the dictation capture" } }.getOrNull()
    }

    /**
     * Writes the codec [frames] of one dictation exactly as they arrived
     * from the watch, so a capture that decodes badly can be re-decoded
     * outside the app and the frame bytes themselves inspected. Layout is
     * [framesBytes]; pruning and failure handling mirror [write].
     */
    fun writeFrames(frames: List<ByteArray>): String? {
        val dir = dictationCaptureDirectory() ?: return null
        return runCatching {
            val directory = Path(dir)
            SystemFileSystem.createDirectories(directory)
            val file = Path(directory, "$PREFIX${Clock.System.now().toEpochMilliseconds()}$FRAMES_SUFFIX")
            SystemFileSystem.sink(file).buffered().use { it.write(framesBytes(frames)) }
            prune(directory, FRAMES_SUFFIX)
            file.toString()
        }.onFailure { logger.w(it) { "Could not write the dictation frame capture" } }.getOrNull()
    }

    private fun prune(directory: Path, suffix: String) {
        val names = SystemFileSystem.list(directory).map { it.name }
        for (stale in captureNamesToPrune(names, KEEP, suffix)) {
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

    /** A canonical 44-byte-header PCM WAV around [pcm16], mono, 16-bit. */
    internal fun wavBytes(pcm16: ByteArray, sampleRate: Int): ByteArray {
        val header = kotlinx.io.Buffer()
        val blockAlign = 2
        header.writeString("RIFF")
        header.writeIntLe(36 + pcm16.size)
        header.writeString("WAVE")
        header.writeString("fmt ")
        header.writeIntLe(16)
        header.writeShortLe(1) // PCM
        header.writeShortLe(1) // mono
        header.writeIntLe(sampleRate)
        header.writeIntLe(sampleRate * blockAlign)
        header.writeShortLe(blockAlign.toShort())
        header.writeShortLe(16) // bits per sample
        header.writeString("data")
        header.writeIntLe(pcm16.size)
        val out = ByteArray(44 + pcm16.size)
        header.readAtMostTo(out, 0, 44)
        pcm16.copyInto(out, 44)
        return out
    }
}

/** Directory for debug dictation captures, or null where none exists. */
internal expect fun dictationCaptureDirectory(): String?
