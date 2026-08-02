package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the PebbleKit server library's request protocol.
 *
 * [PebbleSenderReceiver] authorizes start and stop requests, and translates watch identifiers, by
 * reading keys out of the request Bundle. Those keys are internal to the library and are not
 * exposed as public API, so nothing at compile time notices if a library upgrade renames them.
 * The failure mode matters: the interception would stop recognising start and stop requests and
 * would wave them through unauthorized, silently, while everything still built and ran.
 *
 * This inspects the library's own compiled dispatch class for the constant-pool entries it
 * encodes. Entries are matched exactly (tag, length prefix, bytes), not as substrings, so a
 * rename that extends a key (ACTION to REQUEST_ACTION, say) fails the pin instead of slipping
 * past on the embedded old literal. That proves the strings still exist there, not that they
 * still mean the same thing, so treat a failure as "read the library's dispatch code again"
 * rather than "adjust the constant".
 */
class PebbleKitRequestProtocolTest {

    @Test
    fun `library dispatch still encodes the request keys we depend on`() {
        val classBytes = binderClassBytes()

        listOf(
            "ACTION",
            "WATCHAPP_UUID",
            "WATCHES_ID",
            "TRANSMISSION_RESULTS",
            "START_APP",
            "STOP_APP",
        ).forEach { literal ->
            assertTrue(
                classBytes.containsUtf8Entry(literal),
                "PebbleKit request protocol changed: '$literal' is no longer a constant-pool " +
                    "entry of $BINDER_CLASS. The start/stop authorization in " +
                    "PebbleSenderReceiver keys off these literals and is inert until they are " +
                    "corrected.",
            )
        }
    }

    @Test
    fun `the pin can actually fail`() {
        // Without this, a broken resource load or a botched search would make every assertion
        // above pass vacuously, and the tripwire would look green while guarding nothing.
        assertFalse(
            binderClassBytes().containsUtf8Entry("NOT_A_PEBBLEKIT_PROTOCOL_KEY"),
            "control string was found, so the constant-pool search is not discriminating",
        )
    }

    @Test
    fun `the pin distinguishes an entry from its substrings`() {
        // "ACTIO" occurs inside the ACTION entry's bytes; only the length prefix tells them
        // apart. If this matches, the exact-entry search has regressed to substring matching,
        // which is blind to exactly the extension renames the pin exists to catch.
        assertFalse(
            binderClassBytes().containsUtf8Entry("ACTIO"),
            "a strict substring of a real key matched, so entry lengths are not being checked",
        )
    }

    private fun binderClassBytes(): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(BINDER_CLASS)
        assertNotNull(stream, "could not load $BINDER_CLASS from the test classpath")
        return stream.use { it.readBytes() }
    }

    /**
     * Whether the class bytes contain a CONSTANT_Utf8 constant-pool entry that is exactly
     * [literal]: tag byte 1, two-byte big-endian length equal to the literal's length, then the
     * literal's bytes. The length check is what rejects superstring entries.
     */
    private fun ByteArray.containsUtf8Entry(literal: String): Boolean {
        val bytes = literal.encodeToByteArray()
        val target = ByteArray(3 + bytes.size)
        target[0] = 1
        target[1] = (bytes.size ushr 8).toByte()
        target[2] = bytes.size.toByte()
        bytes.copyInto(target, 3)

        outer@ for (start in 0..(size - target.size)) {
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val BINDER_CLASS =
            "io/rebble/pebblekit2/server/BasePebbleSenderReceiver\$Binder.class"
    }
}
