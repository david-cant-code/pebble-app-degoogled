package io.rebble.libpebblecommon.pebblekit.two

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fork: pins the sender service's admission decision. The toggle refuses before anything
 * else, an unresolvable caller is refused, START and STOP need the companion relationship,
 * and every other action proceeds with the caller attached.
 */
class PebbleKitRequestDecisionTest {
    private val companion = "com.example.companion"
    private val watchapp = "864369ab-1f37-4a2e-9243-dd6b21af9c14"
    private val registry: (String, String?) -> Boolean = { pkg, app -> pkg == companion && app == watchapp }

    @Test
    fun theToggleRefusesEveryRequestFirst() {
        assertEquals(
            PebbleKitRequestDecision.Refuse(PebbleKitRequestRefusal.Disabled),
            pebbleKitRequestDecision(false, companion, "START_APP", watchapp, registry),
        )
        assertEquals(
            PebbleKitRequestDecision.Refuse(PebbleKitRequestRefusal.Disabled),
            pebbleKitRequestDecision(false, companion, "SEND_DATA_TO_WATCH", watchapp, registry),
        )
    }

    @Test
    fun anUnresolvableCallerIsRefused() {
        assertEquals(
            PebbleKitRequestDecision.Refuse(PebbleKitRequestRefusal.UnresolvableCaller),
            pebbleKitRequestDecision(true, null, "SEND_DATA_TO_WATCH", watchapp, registry),
        )
    }

    @Test
    fun startAndStopNeedTheCompanionRelationship() {
        for (action in listOf("START_APP", "STOP_APP")) {
            assertEquals(
                PebbleKitRequestDecision.Proceed(companion),
                pebbleKitRequestDecision(true, companion, action, watchapp, registry),
            )
            assertEquals(
                PebbleKitRequestDecision.Refuse(PebbleKitRequestRefusal.Unauthorized),
                pebbleKitRequestDecision(true, "com.example.other", action, watchapp, registry),
            )
            assertEquals(
                PebbleKitRequestDecision.Refuse(PebbleKitRequestRefusal.Unauthorized),
                pebbleKitRequestDecision(true, companion, action, null, registry),
            )
        }
    }

    @Test
    fun otherActionsProceedWithTheCallerForTheLibraryChecks() {
        assertEquals(
            PebbleKitRequestDecision.Proceed("com.example.other"),
            pebbleKitRequestDecision(true, "com.example.other", "SEND_DATA_TO_WATCH", watchapp, registry),
        )
        assertEquals(
            PebbleKitRequestDecision.Proceed("com.example.other"),
            pebbleKitRequestDecision(true, "com.example.other", null, null, registry),
        )
    }
}
