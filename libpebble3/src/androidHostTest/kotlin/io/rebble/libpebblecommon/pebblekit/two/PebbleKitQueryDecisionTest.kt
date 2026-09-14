package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork: pins the `.pebblekit` provider's query admission. The toggle refuses before the registry
 * is consulted, an unreadable setting counts as off, and a caller the registry does not know is
 * refused. It covers the decision, not that `query` calls it: no host test can build the
 * provider, whose base class needs the library's Java 21 runtime.
 */
class PebbleKitQueryDecisionTest {
    private val companion = "com.example.companion"
    private val registry: (String) -> Boolean = { it == companion }

    @Test
    fun theToggleRefusesBeforeTheRegistryIsConsulted() {
        var consulted = false
        val decision = pebbleKitQueryDecision(
            pebbleKit2Enabled = { false },
            caller = companion,
            isAuthorized = { consulted = true; true },
        )
        assertEquals(PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Disabled), decision)
        assertTrue(!consulted, "the registry was consulted although PebbleKit 2 is off")
    }

    @Test
    fun aSettingThatCannotBeReadCountsAsOff() {
        assertEquals(
            PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Disabled),
            pebbleKitQueryDecision({ null }, companion, registry),
        )
        assertEquals(
            PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Disabled),
            pebbleKitQueryDecision({ throw IllegalStateException("no graph yet") }, companion, registry),
        )
    }

    @Test
    fun aCallerTheRegistryDoesNotKnowIsRefused() {
        assertEquals(
            PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Unauthorized),
            pebbleKitQueryDecision({ true }, "com.example.other", registry),
        )
        assertEquals(
            PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Unauthorized),
            pebbleKitQueryDecision({ true }, null, registry),
        )
    }

    @Test
    fun anAuthorizedCallerIsAdmittedWhilePebbleKit2IsOn() {
        assertEquals(
            PebbleKitQueryDecision.Admit(companion),
            pebbleKitQueryDecision({ true }, companion, registry),
        )
    }
}
