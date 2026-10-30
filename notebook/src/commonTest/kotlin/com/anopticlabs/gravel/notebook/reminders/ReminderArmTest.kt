package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderArmTest {

    private val now = 1_700_000_000_000L

    @Test
    fun usableFutureArmsAtTheReadInstant() {
        val p = ReminderProposal("Call mom", now + 3_600_000L, ReminderTimeStatus.USABLE)
        assertEquals(ReminderArmPlan(now + 3_600_000L, defaulted = false), planReminderArm(p, 30, now))
    }

    @Test
    fun aUsableTimeGoneStaleArmsAtTheDefaultToCheck() {
        val toCheck = ReminderArmPlan(now + 30L * 60_000L, defaulted = true, toCheck = true)
        assertEquals(toCheck, planReminderArm(ReminderProposal("m", now - 1L, ReminderTimeStatus.USABLE), 30, now))
        assertEquals(toCheck, planReminderArm(ReminderProposal("m", now, ReminderTimeStatus.USABLE), 30, now))
        assertEquals(toCheck, planReminderArm(ReminderProposal("m", null, ReminderTimeStatus.USABLE), 30, now))
    }

    @Test
    fun aTimeToCheckArmsAtTheClampedDefaultToCheck() {
        val p = ReminderProposal("Call Sam", null, ReminderTimeStatus.TO_CHECK)
        assertEquals(ReminderArmPlan(now + 45L * 60_000L, defaulted = true, toCheck = true), planReminderArm(p, 45, now))
        assertEquals(ReminderArmPlan(now + 60_000L, defaulted = true, toCheck = true), planReminderArm(p, 0, now))
    }

    @Test
    fun noTimeArmsAtTheClampedDefault() {
        val p = ReminderProposal("Water plants", null, ReminderTimeStatus.NO_TIME)
        assertEquals(ReminderArmPlan(now + 45L * 60_000L, defaulted = true), planReminderArm(p, 45, now))
        assertEquals(ReminderArmPlan(now + 60_000L, defaulted = true), planReminderArm(p, 0, now))
        assertEquals(ReminderArmPlan(now + 60_000L, defaulted = true), planReminderArm(p, -100, now))
        assertEquals(ReminderArmPlan(now + MAX_QUICK_REMIND_MINUTES * 60_000L, defaulted = true), planReminderArm(p, Int.MAX_VALUE, now))
    }
}
