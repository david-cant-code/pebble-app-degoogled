package io.rebble.libpebblecommon.pebblekit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: pins that a toggle a component cannot read yet fails closed. */
class ToggleReadTest {

    @Test
    fun onlyAReadableTrueAllows() {
        assertTrue(toggleAllows { true })
        assertFalse(toggleAllows { false })
    }

    @Test
    fun anUnreadableSettingIsOff() {
        assertFalse(toggleAllows { null }, "a missing binding allowed the call")
        assertFalse(toggleAllows { throw IllegalStateException("Koin not started") }, "a throwing lookup allowed the call")
    }
}
