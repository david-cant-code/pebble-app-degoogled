package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
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
 * This inspects the library's own compiled dispatch class for the literals it encodes. That
 * proves the strings still exist there, not that they still mean the same thing, so treat a
 * failure as "read the library's dispatch code again" rather than "adjust the constant".
 */
class PebbleKitRequestProtocolTest {

    @Test
    fun `library dispatch still encodes the request keys we depend on`() {
        val constantPool = binderClassBytes()

        listOf(
            "ACTION",
            "WATCHAPP_UUID",
            "WATCHES_ID",
            "TRANSMISSION_RESULTS",
            "START_APP",
            "STOP_APP",
        ).forEach { literal ->
            assertTrue(
                constantPool.contains(literal),
                "PebbleKit request protocol changed: '$literal' is no longer present in " +
                    "$BINDER_CLASS. The start/stop authorization in PebbleSenderReceiver keys " +
                    "off these literals and is inert until they are corrected.",
            )
        }
    }

    @Test
    fun `the pin can actually fail`() {
        // Without this, a broken resource load or a botched decode would make every assertion
        // above pass vacuously, and the tripwire would look green while guarding nothing.
        assertTrue(
            !binderClassBytes().contains("NOT_A_PEBBLEKIT_PROTOCOL_KEY"),
            "control string was found, so the constant-pool search is not discriminating",
        )
    }

    private fun binderClassBytes(): String {
        val stream = javaClass.classLoader?.getResourceAsStream(BINDER_CLASS)
        assertNotNull(stream, "could not load $BINDER_CLASS from the test classpath")
        // Latin-1 keeps every byte a distinct character, so constant-pool UTF-8 entries survive
        // the decode intact and can be searched as plain substrings.
        return stream.use { it.readBytes().toString(Charsets.ISO_8859_1) }
    }

    private companion object {
        const val BINDER_CLASS =
            "io/rebble/pebblekit2/server/BasePebbleSenderReceiver\$Binder.class"
    }
}
