package coredevices.util.transcription

import co.touchlab.kermit.Logger
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
            val names = SystemFileSystem.list(directory).map { it.name }
            for (stale in captureNamesToPrune(names, KEEP)) {
                SystemFileSystem.delete(Path(directory, stale), mustExist = false)
            }
            file.toString()
        }.onFailure { logger.w(it) { "Could not write the dictation capture" } }.getOrNull()
    }

    /**
     * The capture file names to delete so that at most [keep] remain: the
     * epoch-millisecond stamp in the name orders them, so the lexically
     * smallest are the oldest. Non-capture names are left alone.
     */
    internal fun captureNamesToPrune(names: Collection<String>, keep: Int): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
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
