package com.anopticlabs.gravel.notebook.alarms

import kotlin.test.Test
import kotlin.test.assertEquals

class SetNoticeCapTest {

    @Test
    fun aNewNoticeKeepsTheNewestOthersUpToTheCapAndTakesDownTheRest() {
        val others = (1..MAX_SET_NOTICES).map { id -> id to 1_000L * id }.shuffled()
        assertEquals(listOf(1), setNoticesPastCap(others))
        assertEquals(emptyList(), setNoticesPastCap(others.filter { it.first != 1 }))
        assertEquals(listOf(2, 1), setNoticesPastCap(others + (11 to 11_000L)))
    }
}
