package com.anopticlabs.gravel.notebook.backup

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupPasswordTest {

    @Test
    fun aBackupPasswordNeedsFifteenCodePointsTypedTheSameTwice() {
        assertEquals(PasswordProblem.TOO_SHORT, backupPasswordProblem("fourteen chars", "fourteen chars"))
        assertNull(backupPasswordProblem("fifteen chars!!", "fifteen chars!!"))
        assertEquals(PasswordProblem.DIFFERENT, backupPasswordProblem("fifteen chars!!", "fifteen chars!?"))
        assertEquals(PasswordProblem.DIFFERENT, backupPasswordProblem("fifteen chars!!", ""))
        assertEquals(PasswordProblem.TOO_SHORT, backupPasswordProblem("😀".repeat(14), "😀".repeat(14)))
        assertNull(backupPasswordProblem("😀".repeat(15), "😀".repeat(15)))
        assertEquals(PasswordProblem.NOT_TEXT, backupPasswordProblem("abcdefghijklmnop\uD800", "abcdefghijklmnop\uD800"))
    }

    @Test
    fun theTwoEntriesAreComparedAndMeasuredAfterNormalizing() {
        val composed = Normalizer.normalize("café crème brûlée", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertNull(backupPasswordProblem(composed, decomposed))
        val fourteen = Normalizer.normalize("abcdefghijklmé", Normalizer.Form.NFD)
        assertEquals(15, fourteen.length)
        assertEquals(PasswordProblem.TOO_SHORT, backupPasswordProblem(fourteen, fourteen))
    }
}
