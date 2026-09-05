package coredevices.util.transcription

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the WAV layout a replay tool will parse, the prune order that
 * keeps the newest captures, and the clear that leaves none.
 */
class DictationCaptureDumpTest {

    private fun ByteArray.leInt(at: Int): Int =
        (this[at].toInt() and 0xFF) or
            ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or
            ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.leShort(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun wavHeaderDescribesMono16BitPcm() {
        val pcm = ByteArray(1000) { it.toByte() }
        val wav = DictationCaptureDump.wavBytes(pcm, 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals(36 + pcm.size, wav.leInt(4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("fmt ", wav.decodeToString(12, 16))
        assertEquals(16, wav.leInt(16))
        assertEquals(1, wav.leShort(20), "PCM format tag")
        assertEquals(1, wav.leShort(22), "mono")
        assertEquals(16_000, wav.leInt(24))
        assertEquals(32_000, wav.leInt(28), "byte rate")
        assertEquals(2, wav.leShort(32), "block align")
        assertEquals(16, wav.leShort(34), "bits per sample")
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(pcm.size, wav.leInt(40))
        assertContentEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun pruneKeepsTheNewestCapturesAndIgnoresOtherFiles() {
        val names = listOf(
            "dictation-1700000000003.wav",
            "dictation-1700000000001.wav",
            "notes.txt",
            "dictation-1700000000002.wav",
            "dictation-1700000000004.wav",
        )
        assertEquals(
            listOf("dictation-1700000000001.wav", "dictation-1700000000002.wav"),
            DictationCaptureDump.captureNamesToPrune(names, keep = 2),
        )
        assertEquals(emptyList(), DictationCaptureDump.captureNamesToPrune(names, keep = 10))
    }

    @Test
    fun framesAreLengthPrefixedInArrivalOrder() {
        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(), byteArrayOf(9))
        val bytes = DictationCaptureDump.framesBytes(frames)
        assertEquals(2 + 3 + 2 + 0 + 2 + 1, bytes.size)
        assertEquals(3, bytes.leShort(0))
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.copyOfRange(2, 5))
        assertEquals(0, bytes.leShort(5))
        assertEquals(1, bytes.leShort(7))
        assertEquals(9, bytes[9].toInt())
    }

    @Test
    fun clearDeletesEveryCaptureAndNothingElse() {
        val directory = Path(SystemTemporaryDirectory, "captures-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        for (name in listOf("dictation-1700000000001.wav", "dictation-1700000000002.wav", "dictation-1700000000001.spx", "notes.txt")) {
            SystemFileSystem.sink(Path(directory, name)).buffered().use { it.writeString("x") }
        }
        DictationCaptureDump.clear(directory)
        assertEquals(listOf("notes.txt"), SystemFileSystem.list(directory).map { it.name })
        // A directory that never existed is not an error.
        DictationCaptureDump.clear(Path(directory, "missing"))
    }

    @Test
    fun pruneIsPerSuffix() {
        val names = listOf(
            "dictation-1700000000001.wav",
            "dictation-1700000000001.spx",
            "dictation-1700000000002.spx",
            "dictation-1700000000003.spx",
        )
        assertEquals(
            listOf("dictation-1700000000001.spx"),
            DictationCaptureDump.captureNamesToPrune(names, keep = 2, suffix = ".spx"),
        )
        assertEquals(emptyList(), DictationCaptureDump.captureNamesToPrune(names, keep = 2))
    }
}
