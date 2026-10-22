package com.anopticlabs.gravel.notebook.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyResetTest {
    private var deleted = 0
    private var forgotten = 0

    private fun reset(force: Boolean, lookup: () -> KeyLookup, delete: () -> Unit = { deleted++ }, absentIsCertain: Boolean = true) =
        NotebookKeystore.resetWith(force, absentIsCertain, lookup, delete, forget = { forgotten++ })

    @Test
    fun aKeyAndroidReportsAbsentIsOnlyForgottenAndOneThatWorksIsKept() {
        for (force in listOf(false, true)) {
            assertEquals(KeyReset.DELETED, reset(force, { KeyLookup.ABSENT }))
            assertEquals(KeyReset.KEY_WORKS, reset(force, { KeyLookup.WORKS }))
        }
        assertEquals(0, deleted)
        assertEquals(2, forgotten)
    }

    @Test
    fun aKeyThatFailsOrCannotBeLookedUpIsDeletedOnlyWhenForced() {
        val lookups = listOf<() -> KeyLookup>({ KeyLookup.FAILS }, { KeyLookup.UNREADABLE }, { throw IllegalStateException("key store down") })
        for (lookup in lookups) assertEquals(KeyReset.KEPT, reset(force = false, lookup))
        assertEquals(0, deleted)
        for (lookup in lookups) assertEquals(KeyReset.DELETED, reset(force = true, lookup))
        assertEquals(3, deleted)
        assertEquals(0, forgotten)
    }

    @Test
    fun anAbsentKeyAndroidCannotVouchForIsClearedOnlyWhenForced() {
        assertEquals(KeyReset.KEPT, reset(force = false, { KeyLookup.ABSENT }, absentIsCertain = false))
        assertEquals(KeyReset.DELETED, reset(force = true, { KeyLookup.ABSENT }, absentIsCertain = false))
        assertEquals(1, deleted)
        assertEquals(0, forgotten)
    }

    @Test
    fun aForcedDeleteThatFailsIsReportedAsFailed() {
        assertEquals(KeyReset.FAILED, reset(force = true, { KeyLookup.FAILS }, delete = { throw IllegalStateException("key store down") }))
    }
}
