package com.anopticlabs.gravel.notebook.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteDisplayTest {

    private fun check(text: String): List<String> {
        val chunks = NoteDisplay.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= NoteDisplay.CHUNK_CHARS })
        return chunks
    }

    @Test
    fun aShortTextIsOneChunk() {
        assertEquals(listOf(""), check(""))
        assertEquals(listOf("one line\ntwo"), check("one line\ntwo"))
    }

    @Test
    fun aLongTextSplitsAfterItsLastLineBreakInEachPiece() {
        val line = "x".repeat(99) + "\n"
        val chunks = check(line.repeat(100))
        assertTrue(chunks.dropLast(1).all { it.endsWith("\n") })
        assertEquals(3, chunks.size)
    }

    @Test
    fun aTextWithoutLineBreaksSplitsAfterASpaceOrElseAtTheLimit() {
        assertTrue(check("word ".repeat(3000)).dropLast(1).all { it.endsWith(" ") })
        assertEquals(NoteDisplay.CHUNK_CHARS, check("y".repeat(10_000)).first().length)
    }

    @Test
    fun aSplitNeverSeparatesASurrogatePair() {
        val text = "a".repeat(NoteDisplay.CHUNK_CHARS - 1) + "😀" + "b".repeat(10)
        val chunks = check(text)
        assertEquals(NoteDisplay.CHUNK_CHARS - 1, chunks.first().length)
    }
}
