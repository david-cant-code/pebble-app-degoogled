package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The NAME column is served to every authorized companion, so it must carry nothing more
 * specific than the watch model: the advertised name's device-unique suffix (or a user
 * nickname) is identical for every caller and would hand two companions the shared correlator
 * the per-caller pseudonymous identifiers exist to remove.
 */
class PebbleKitWatchNameTest {

    @Test
    fun `strips the device-unique suffix from advertised names`() {
        assertEquals("Pebble", pebbleKitWatchName("Pebble 4F2A"))
        assertEquals("Pebble Time", pebbleKitWatchName("Pebble Time 807A"))
        assertEquals("Pebble Time Le", pebbleKitWatchName("Pebble Time Le 12ab"))
    }

    @Test
    fun `strips the suffix from unrecognised model prefixes too`() {
        // A future device following the same suffix convention gets the same treatment.
        assertEquals("Core Time 2", pebbleKitWatchName("Core Time 2 B3C4"))
    }

    @Test
    fun `keeps model names that end in a short numeral`() {
        // "2" is a hex digit, but the device suffix is always four; the model name survives.
        assertEquals("Pebble 2", pebbleKitWatchName("Pebble 2"))
        assertEquals("Core Time 2", pebbleKitWatchName("Core Time 2"))
    }

    @Test
    fun `keeps names with a non-hex final word`() {
        assertEquals("Pebble Steel", pebbleKitWatchName("Pebble Steel"))
    }
}
