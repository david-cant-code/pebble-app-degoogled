package com.anopticlabs.gravel.notebook.storage

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistedFormatTest {

    private fun boom() = SerializationException("injected parse failure")

    @Test
    fun strictWrapsAThrowingParseWithTheLabelAndCause() {
        val e = assertFailsWith<PersistedFormatException> {
            parsePersisted(strict = true, label = "notes_meta", empty = { emptyList<String>() }) { throw boom() }
        }
        assertTrue(e.message!!.contains("notes_meta"))
        assertIs<SerializationException>(e.cause)
    }

    @Test
    fun nonStrictReturnsEmptyForAThrowingParse() {
        val result = parsePersisted(strict = false, label = "reminders", empty = { listOf("default") }) { throw boom() }
        assertEquals(listOf("default"), result)
    }

    @Test
    fun aNullParseIsEmptyInBothModes() {
        assertEquals("empty", parsePersisted(strict = true, label = "x", empty = { "empty" }) { null })
        assertEquals("empty", parsePersisted(strict = false, label = "x", empty = { "empty" }) { null })
    }

    @Test
    fun aValuePassesThroughInBothModes() {
        assertEquals(7, parsePersisted(strict = true, label = "x", empty = { 0 }) { 7 })
        assertEquals(7, parsePersisted(strict = false, label = "x", empty = { 0 }) { 7 })
    }

    @Test
    fun storageFailuresRaisedInsideParseKeepTheirClassInBothModes() {
        val transient = TransientCryptoException("t", RuntimeException())
        val missing = KeyUnavailableException("missing")
        val format = PersistedFormatException("inner", boom())
        for (strict in listOf(true, false)) {
            assertSame(transient, assertFailsWith<TransientCryptoException> { parsePersisted(strict, "x", { 0 }) { throw transient } })
            assertSame(missing, assertFailsWith<KeyUnavailableException> { parsePersisted(strict, "x", { 0 }) { throw missing } })
            assertSame(format, assertFailsWith<PersistedFormatException> { parsePersisted(strict, "x", { 0 }) { throw format } })
        }
    }

    @Test
    fun formatFailureIsFoundDirectlyAndThroughWrappers() {
        val direct = PersistedFormatException("f", boom())
        assertTrue(isPersistedFormatFailure(direct))
        assertTrue(isPersistedFormatFailure(RuntimeException("outer", RuntimeException("mid", direct))))
    }

    @Test
    fun formatFailureExcludesNullUnrelatedAndTransient() {
        assertFalse(isPersistedFormatFailure(null))
        assertFalse(isPersistedFormatFailure(RuntimeException("plain")))
        assertFalse(isPersistedFormatFailure(TransientCryptoException("t", RuntimeException())))
    }
}
