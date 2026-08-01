package coredevices.util

import coredevices.util.models.CactusSTTMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the fork's STT default. Upstream defaults to RemoteOnly, which needs
 * the Core-account sign-in this fork removed; an upstream merge restoring
 * that default would put fresh installs (and any install that never set the
 * mode explicitly, since unset fields fall back to the compiled default) on
 * a mode that can never work and that the settings dropdown no longer
 * offers.
 */
class SttDefaultTest {
    @Test
    fun sttDefaultsToLocalOnly() {
        assertEquals(CactusSTTMode.LocalOnly, STTConfig().mode)
    }
}
