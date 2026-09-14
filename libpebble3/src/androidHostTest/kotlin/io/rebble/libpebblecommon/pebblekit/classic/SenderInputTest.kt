package io.rebble.libpebblecommon.pebblekit.classic

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Fork: pins the width of the per-broadcast catch around exported classic PebbleKit receivers.
 * Errors are part of it (see handleSenderInput for why), and cancellation is not.
 */
class SenderInputTest {

    @Test
    fun aValueFromTheHandlingIsReturned() {
        assertEquals(7, handleSenderInput(onDropped = { fail("a successful handling was reported as dropped") }) { 7 })
    }

    @Test
    fun anyThrowableFromTheHandlingIsDroppedAndReported() {
        val dropped = mutableListOf<Throwable>()
        assertNull(handleSenderInput(onDropped = { dropped += it }) { throw IllegalArgumentException("bad base64") })
        assertNull(handleSenderInput(onDropped = { dropped += it }) { throw NullPointerException() })
        assertNull(handleSenderInput(onDropped = { dropped += it }) { throw StackOverflowError() })
        assertNull(handleSenderInput(onDropped = { dropped += it }) { throw OutOfMemoryError() })
        assertEquals(
            listOf(IllegalArgumentException::class, NullPointerException::class, StackOverflowError::class, OutOfMemoryError::class),
            dropped.map { it::class },
        )
    }

    @Test
    fun cancellationIsNotDropped() {
        assertFailsWith<CancellationException> {
            handleSenderInput(onDropped = { fail("cancellation was dropped") }) { throw CancellationException("stopped") }
        }
    }
}
