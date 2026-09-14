package io.rebble.libpebblecommon.pebblekit.classic

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Fork: pins how the exported classic receivers read the watchapp UUID extra. Any app can put
 * anything under that key; a read that throws, errors included, and a value that is not a UUID
 * both count as no UUID, and the sender's string does not reach the log.
 */
class WatchappUuidExtraTest {
    private val uuid = Uuid.parse("864369ab-1f37-4a2e-9243-dd6b21af9c14")

    private class CapturingLogWriter : LogWriter() {
        val messages = CopyOnWriteArrayList<String>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            messages += message
        }
    }

    private val capture = CapturingLogWriter()
    private lateinit var writersBefore: List<LogWriter>

    @BeforeTest
    fun captureLog() {
        writersBefore = Logger.config.logWriterList
        Logger.addLogWriter(capture)
    }

    @AfterTest
    fun restoreLog() {
        Logger.setLogWriters(writersBefore)
    }

    @Test
    fun readsAJavaUuidOrItsStringForm() {
        assertEquals(uuid, watchappUuidFrom { UUID.fromString(uuid.toString()) })
        assertEquals(uuid, watchappUuidFrom { uuid.toString() })
    }

    @Test
    fun anUnreadableOrMalformedExtraIsNoUuid() {
        assertNull(watchappUuidFrom { throw RuntimeException("ClassNotFoundException reading a Serializable object") })
        assertNull(watchappUuidFrom { throw StackOverflowError() })
        assertNull(watchappUuidFrom { "not a uuid" })
        assertNull(watchappUuidFrom { 42 })
        assertNull(watchappUuidFrom { null })
    }

    @Test
    fun theMessageOfAReadThatThrewIsNotWrittenToTheLog() {
        // The message of the read's throwable quotes a class name the sender chose.
        val forged = "x\n2026-09-13T10:00:00.000Z [E] PebbleKitClassic: forged by the sender"
        assertNull(watchappUuidFrom { throw RuntimeException(forged) })
        assertTrue(capture.messages.isNotEmpty(), "nothing was logged, so this test cannot see what would be")
        assertTrue(
            capture.messages.none { "forged by the sender" in it },
            "the throwable's message reached the log: ${capture.messages}",
        )
    }

    @Test
    fun aMalformedUuidStringIsNotWrittenToTheLog() {
        val forged = "x\n2026-09-13T10:00:00.000Z [E] PebbleKitClassic: forged"
        assertNull(watchappUuidFrom { forged })
        assertTrue(capture.messages.isNotEmpty(), "nothing was logged, so this test cannot see what would be")
        assertTrue(capture.messages.none { "forged" in it }, "the sender's string reached the log: ${capture.messages}")
    }
}
