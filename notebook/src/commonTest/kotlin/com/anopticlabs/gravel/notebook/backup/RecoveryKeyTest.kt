package com.anopticlabs.gravel.notebook.backup

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryKeyTest {

    private val key = "0o1lzz9a" + "bcdefghi".repeat(7)

    @Test
    fun aGeneratedKeyIsSixtyFourDrawsOverTheWholeAlphabet() {
        val bounds = mutableListOf<Int>()
        var n = 0
        val k = RecoveryKey.generate { bound -> bounds += bound; n++ % bound }
        assertEquals(RecoveryKey.ALPHABET + RecoveryKey.ALPHABET.take(28), k.concatToString())
        assertEquals(List(64) { 36 }, bounds)
        assertTrue(RecoveryKey.generate { Random.nextInt(it) }.all { it in RecoveryKey.ALPHABET })
    }

    @Test
    fun theKeyIsShownUppercaseInGroupsOfFourWithOAndZeroSetApart() {
        val shown = RecoveryKey.display(key.toCharArray())
        val lines = shown.lines()
        assertEquals(4, lines.size)
        assertEquals("=#1L ZZ9A BCDE FGHI", lines[0])
        assertTrue(lines.all { it.split(' ').size == 4 && it.split(' ').all { g -> g.length == 4 } })
        assertTrue('O' !in shown && '0' !in shown)
    }

    @Test
    fun theShownFormAndTheStoredFormParseToTheKey() {
        assertEquals(key, RecoveryKey.parse(RecoveryKey.display(key.toCharArray()))?.concatToString())
        assertEquals(key, RecoveryKey.parse(key)?.concatToString())
        assertEquals(key, RecoveryKey.parse(key.uppercase().chunked(4).joinToString("-"))?.concatToString())
        assertEquals(key, RecoveryKey.parse("  " + key.chunked(8).joinToString("\n\t") + " ")?.concatToString())
    }

    @Test
    fun aKeyOfAnotherLengthOrWithOtherCharactersDoesNotParse() {
        assertNull(RecoveryKey.parse(key.dropLast(1)))
        assertNull(RecoveryKey.parse(key + "a"))
        assertNull(RecoveryKey.parse(""))
        assertNull(RecoveryKey.parse(key + "\u00e9"), "a non-ASCII letter is not dropped like punctuation")
        assertNull(RecoveryKey.parse(key.take(10) + "\u0430" + key.drop(10)))
    }
}
