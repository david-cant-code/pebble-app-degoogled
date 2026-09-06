package coredevices.util.transcription

import coredevices.util.deleteRecursive
import coredevices.util.writeWavHeader
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two capture files' contents and names, the prune order that
 * keeps the newest captures, and the clear that leaves none. The WAV
 * header itself is pinned by WAVHeaderTest.
 */
class DictationCaptureDumpTest {

    /** A directory of this test's own under the temporary directory; the file tests write into it and it is removed afterwards. */
    private val directory = Path(SystemTemporaryDirectory, "captures-${Random.nextLong()}")

    @AfterTest
    fun cleanup() = deleteRecursive(directory)

    private fun ByteArray.leShort(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun writeStoresTheWavHeaderThenThePcmUnderAStampedName() {
        val pcm = ByteArray(1000) { it.toByte() }
        val path = DictationCaptureDump.write(directory, pcm, 16_000)
        val file = Path(path!!)
        assertTrue(file.name.startsWith("dictation-") && file.name.endsWith(".wav"), file.name)
        val expected = Buffer().apply {
            writeWavHeader(16_000, pcm.size)
            write(pcm)
        }.readByteArray()
        assertContentEquals(expected, SystemFileSystem.source(file).buffered().use { it.readByteArray() })
    }

    @Test
    fun writeFramesStoresTheFrameLayoutUnderAStampedName() {
        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(9))
        val path = DictationCaptureDump.writeFrames(directory, frames)
        val file = Path(path!!)
        assertTrue(file.name.startsWith("dictation-") && file.name.endsWith(".spx"), file.name)
        assertContentEquals(
            DictationCaptureDump.framesBytes(frames),
            SystemFileSystem.source(file).buffered().use { it.readByteArray() },
        )
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
