package com.anopticlabs.gravel.notebook.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerCheckTest {

    private val check = ServerCheck()

    /** One request for [id]: takes its let-through, then reports a server error. */
    private fun fails(id: String, stamp: Int = 1) = check.failed(id, stamp, check.take(id))

    @Test
    fun aServerErrorHoldsUntilTryAgain() {
        assertFalse(check.held(1))
        assertFalse(fails("a"))
        assertTrue(check.held(1))
        check.tryAgain()
        assertFalse(check.held(1))
    }

    @Test
    fun aHoldWhoseCaptureWentToThePhoneLetsNothingThroughOnTryAgain() {
        check.fellBack(1)
        assertTrue(check.held(1))
        check.tryAgain()
        assertFalse(check.held(1))
        assertNull(check.take("a"))
        check.fellBack(1)
        assertFalse(check.held(2), "a settings change ends it too")
    }

    @Test
    fun aChangeOfTheServerSettingsEndsTheHold() {
        fails("a")
        assertFalse(check.held(2))
        assertFalse(check.held(1), "the hold does not come back with the old settings")
    }

    @Test
    fun theCaptureTryAgainLetThroughIsFlaggedWhenItsNextRequestFails() {
        fails("a")
        check.tryAgain()
        assertTrue(fails("a"))
        assertFalse(check.held(1), "flagging it leaves the server open for the rest")
        assertFalse(fails("a"), "only once: a later failure holds again")
        assertTrue(check.held(1))
    }

    @Test
    fun anotherCaptureFailingFirstAfterTryAgainHoldsAgainAndKeepsTheLetThrough() {
        fails("a")
        check.tryAgain()
        assertFalse(fails("b"))
        assertTrue(check.held(1))
        check.tryAgain()
        assertTrue(fails("a"))
        assertTrue(fails("b"))
    }

    @Test
    fun aSecondTryAgainKeepsTheLetThrough() {
        fails("a")
        check.tryAgain()
        check.tryAgain()
        assertTrue(fails("a"))
    }

    @Test
    fun theLetThroughCoversOneRequestWhateverItsOutcome() {
        fails("a")
        check.tryAgain()
        assertEquals(1, check.take("a"))
        assertNull(check.take("a"))
        assertFalse(fails("a"), "a later server error holds")
        assertTrue(check.held(1))
    }

    @Test
    fun aSettingsChangeAfterTryAgainMakesAFailureHoldInsteadOfFlag() {
        fails("a")
        check.tryAgain()
        assertFalse(fails("a", stamp = 2))
        assertTrue(check.held(2))
    }
}
