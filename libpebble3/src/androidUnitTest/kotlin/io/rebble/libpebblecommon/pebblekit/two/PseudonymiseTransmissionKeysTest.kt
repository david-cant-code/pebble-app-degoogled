package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the outbound half of the identifier translation in [PebbleSenderReceiver]: results keyed
 * by real serial must reach the caller keyed by what it is allowed to see. The drop case is the
 * security-relevant branch: when no pseudonym can be derived, the row disappears rather than
 * falling back to the serial, so an identity-layer failure cannot disclose the identifier the
 * whole scheme exists to hide.
 */
class PseudonymiseTransmissionKeysTest {

    @Test
    fun `echoes an identifier the caller supplied`() {
        // The caller addressed the watch by pseudonym (or legacy serial); echoing exactly what
        // it sent is what lets it correlate the result with its request.
        val mapped = pseudonymiseTransmissionKeys(
            serials = listOf("SERIAL-1"),
            suppliedBySerial = mapOf("SERIAL-1" to "supplied-id"),
            pseudonymFor = { "derived-id" },
        )

        assertEquals(mapOf("SERIAL-1" to "supplied-id"), mapped)
    }

    @Test
    fun `pseudonymises a key the base class generated`() {
        // The "all connected watches" case: the caller supplied nothing, so the serials came
        // from the base class and must be replaced before they leave the process.
        val mapped = pseudonymiseTransmissionKeys(
            serials = listOf("SERIAL-1"),
            suppliedBySerial = emptyMap(),
            pseudonymFor = { serial -> "pseudonym-of-$serial" },
        )

        assertEquals(mapOf("SERIAL-1" to "pseudonym-of-SERIAL-1"), mapped)
    }

    @Test
    fun `drops a serial with no derivable pseudonym`() {
        val mapped = pseudonymiseTransmissionKeys(
            serials = listOf("SERIAL-1"),
            suppliedBySerial = emptyMap(),
            pseudonymFor = { null },
        )

        assertEquals(emptyMap(), mapped)
    }

    @Test
    fun `handles the mixed case independently per serial`() {
        val mapped = pseudonymiseTransmissionKeys(
            serials = listOf("SUPPLIED", "GENERATED", "UNDERIVABLE"),
            suppliedBySerial = mapOf("SUPPLIED" to "as-sent"),
            pseudonymFor = { serial -> "pseudonym-of-$serial".takeIf { serial == "GENERATED" } },
        )

        assertEquals(
            mapOf("SUPPLIED" to "as-sent", "GENERATED" to "pseudonym-of-GENERATED"),
            mapped,
        )
    }
}
