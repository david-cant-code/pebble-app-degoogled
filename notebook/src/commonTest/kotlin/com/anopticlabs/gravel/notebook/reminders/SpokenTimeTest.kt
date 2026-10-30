package com.anopticlabs.gravel.notebook.reminders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

class SpokenTimeTest {

    private val zone = TimeZone.of("America/Los_Angeles")

    private fun at(local: String): Instant = LocalDateTime.parse(local).toInstant(zone)

    /** A Saturday afternoon recording start. */
    private val saturday = at("2026-10-17T16:34:21")
    private val monday = at("2026-10-19T10:00:00")

    private fun read(text: String, anchor: Instant = saturday) = SpokenTimeReader.read(text, anchor, zone)

    private fun localOf(result: SpokenTime): String? =
        (result as? SpokenTime.Read)?.let { Instant.fromEpochMilliseconds(it.atMillis).toLocalDateTime(zone).toString() }

    private fun assertReads(expectedLocal: String, vararg texts: String, anchor: Instant = saturday) {
        for (text in texts) assertEquals(LocalDateTime.parse(expectedLocal).toString(), localOf(read(text, anchor)), text)
    }

    private fun assertAfter(seconds: Long, vararg texts: String, anchor: Instant = saturday) {
        for (text in texts) assertEquals(SpokenTime.Read(anchor.toEpochMilliseconds() + seconds * 1000), read(text, anchor), text)
    }

    private fun assertUnread(vararg texts: String, anchor: Instant = saturday) {
        for (text in texts) assertEquals(SpokenTime.Unread, read(text, anchor), text)
    }

    private fun assertNone(vararg texts: String) {
        for (text in texts) assertEquals(SpokenTime.None, read(text), text)
    }

    @Test
    fun durationsCountFromTheRecordingStart() {
        assertAfter(300, "Remind me to feed the dogs in 5 minutes.", "Remind me to feed the dogs in five minutes.")
        assertAfter(3600, "Remind me to feed the dogs in 1 hour.", "Remind me to feed the dogs in one hour.", "in an hour", "in an hour's time")
        assertAfter(12600, "Remind me to feed the dogs in 3 and a half hours.", "Remind me to feed the dogs in three and a half hours.")
        assertAfter(8340, "Remind me to take out trash in 2 hours 19 minutes.", "Remind me to take out trash in two hours nineteen minutes.")
        assertAfter(3900, "Remind me to take out the trash in one hour and five minutes.", "Remind me to take out the trash in 1 hour and 5 minutes.")
        assertAfter(5400, "in an hour and a half", "in 90 minutes", "in 1.5 hours", "in ninety minutes", "in 1.50 hours")
        assertAfter(4500, "in an hour and a quarter")
        assertAfter(1800, "in half an hour", "in a half hour", "in thirty minutes")
        assertAfter(900, "in a quarter of an hour", "in a quarter hour")
        assertAfter(1500, "in twenty five minutes", "in twenty-five minutes")
        assertAfter(30, "in 30 seconds", "in 30 secs")
        assertAfter(7200, "2 hours from now", "in 2 hours from now")
    }

    @Test
    fun dayDurationsKeepTheWallTimeAndMonthsClampToTheMonthEnd() {
        assertReads("2026-10-19T16:34:21", "in 2 days")
        assertReads("2026-10-24T16:34:21", "in a week", "in a week's time")
        assertReads("2026-10-31T16:34:21", "in two weeks' time", "in two weeks time")
        // Across the fall-back change on 2026-11-01, the same wall time three weeks on.
        assertReads("2026-11-07T16:34:21", "in 3 weeks")
        assertReads("2026-11-17T16:34:21", "in a month")
        assertReads("2027-10-17T16:34:21", "in a year")
        assertReads("2026-10-19T04:34:21", "in a day and a half")
        assertReads("2027-02-28T09:00", "in a month", anchor = at("2027-01-31T09:00"))
        assertReads("2026-10-19T15:00", "in 2 days at 3pm", "in two days at 3")
    }

    @Test
    fun anUnmarkedHourIsTheNextOneComingButNotOvernight() {
        // 16:34 on Saturday: 3 o'clock today has passed and 3 AM is skipped, so 3 PM tomorrow.
        assertReads("2026-10-18T15:00", "at 3", "at three", "at 3 o'clock", "at three o'clock", "at 3 sharp", "at around 3")
        assertReads("2026-10-17T20:00", "at 8")
        assertReads("2026-10-17T17:00", "at 5")
        assertReads("2026-10-18T16:00", "at 4")
        assertReads("2026-10-17T19:30", "at 7:30", "at seven thirty", "at 7 30")
        assertReads("2026-10-18T15:30", "at 3:30", "half past 3", "at half past three")
        assertReads("2026-10-17T19:05", "at seven oh five")
        assertReads("2026-10-17T17:05", "at five o five")
        assertReads("2026-10-17T19:45", "at seven forty five", "at a quarter to 8", "quarter to eight", "at quarter of 8")
        assertReads("2026-10-17T19:50", "at ten before 8")
        assertReads("2026-10-18T15:00", "Remind me 3 o'clock")
        assertReads("2026-10-17T17:15", "quarter past 5")
        assertReads("2026-10-17T17:10", "at ten past five", "10 minutes past 5")
        assertReads("2026-10-17T17:40", "at twenty to six", "20 minutes to 6")
        assertReads("2026-10-19T20:00", "at 8", anchor = monday)
        assertReads("2026-10-20T08:00", "at 8", anchor = at("2026-10-19T22:00"))
        // 5 AM is overnight too, so at 4 AM "at 5" is 5 in the afternoon.
        assertReads("2026-10-19T17:00", "at 5", anchor = at("2026-10-19T04:00"))
        assertReads("2026-10-19T06:00", "at 6", anchor = at("2026-10-19T04:00"))
    }

    @Test
    fun markedTimesAndNoonAndMidnight() {
        assertReads("2026-10-17T17:00", "at 5pm", "at 5 p.m.", "at 5 PM", "5pm")
        assertReads("2026-10-18T10:00", "at 10am", "at 10 a.m.", "10 AM", "at 10:00 a.m.")
        assertReads("2026-10-18T03:00", "at 3 am", "at 3 a.m.")
        assertReads("2026-10-17T19:30", "at 19:30", "at 7:30 pm", "7.30pm")
        assertReads("2026-10-18T09:00", "at 09:00")
        assertReads("2026-10-18T12:00", "at noon", "noon", "at 12 noon", "at midday", "at 12")
        assertReads("2026-10-18T00:00", "at midnight")
    }

    @Test
    fun partsOfTheDaySetTheHourOrSettleAmAndPm() {
        assertReads("2026-10-17T20:00", "tonight", "at night", "at 8 tonight", "I need a reminder for tonight")
        assertReads("2026-10-17T17:00", "Set a reminder for 5 pm to call Sam", "Set it for 5 pm", "Set a reminder for me for 5 pm", "Set reminders for 5 pm")
        assertReads("2026-10-18T06:00", "Set an alarm for 6 am", "Set alarms for 6 am")
        assertReads("2026-10-18T09:00", "Reminder for tomorrow: call Sam", "Remind me for tomorrow")
        assertReads("2026-10-23T09:00", "Put a reminder in for Friday", "Schedule it for Friday")
        assertReads("2026-10-17T17:00", "Make it for 5 pm")
        assertUnread("Remind me to set an alarm for 6 am", "Remind me to book it for Friday", "Remind me to buy a cake, we need it for Friday", "Remind me to schedule it for 3 pm")
        assertReads("2026-10-17T18:00", "this evening", "at 6 at night", "at 6 in the afternoon")
        assertReads("2026-10-17T21:00", "at 9 in the evening", "at 9 tonight", "9 tonight")
        assertReads("2026-10-17T17:00", "Remind me 5 this evening")
        assertReads("2026-10-18T02:00", "at 2 in the morning")
        assertReads("2026-10-18T14:00", "Remind me to take 2 in the afternoon")
        assertReads("2026-10-18T09:00", "in the morning", "tomorrow morning")
        assertReads("2026-10-18T07:00", "at 7 in the morning", "tomorrow morning at 7")
        assertReads("2026-10-18T08:00", "8 in the morning", "Remind me 8 in the morning to call Sam")
        assertReads("2026-10-18T07:30", "seven thirty in the morning")
        assertReads("2026-10-18T15:00", "at 3 in the afternoon")
        assertReads("2026-10-18T12:00", "at 12 in the afternoon")
        assertReads("2026-10-18T16:00", "at 4 in the evening")
        assertReads("2026-10-18T14:00", "tomorrow afternoon")
        assertReads("2026-10-18T18:00", "tomorrow evening")
        assertReads("2026-10-18T20:00", "tomorrow night")
        assertUnread("at 8 pm in the morning", "this morning", "this afternoon", "at 7 in the afternoon", "at 3 in the evening", "at 5 at night")
        assertUnread("at 19:30 in the morning", "at 7 am tonight", "at 9 am tomorrow evening", "tomorrow tonight", "tomorrow at midnight")
    }

    @Test
    fun daysWithoutATimeAreNineInTheMorning() {
        assertReads("2026-10-18T09:00", "tomorrow", "Remind me tomorrow to plan my day")
        assertReads("2026-10-18T10:00", "Remind me to feed the dogs tomorrow at 10am.", "tomorrow at 10 a.m.")
        assertReads("2026-10-18T12:00", "Remind me to feed the dogs tomorrow at noon.")
        assertReads("2026-10-18T15:00", "tomorrow at 3", "at 3 tomorrow", "3pm tomorrow")
        assertReads("2026-10-18T08:00", "tomorrow at 8")
        assertReads("2026-10-18T15:30", "tomorrow at half past 3")
        assertReads("2026-10-19T09:00", "the day after tomorrow", "day after tomorrow")
        assertReads("2026-10-17T18:00", "today at 6")
        assertUnread("today")
    }

    @Test
    fun weekdaysAreTheComingOne() {
        assertReads("2026-10-23T09:00", "on Friday", "Friday", "this Friday", "next Friday", "this coming Friday")
        assertReads("2026-10-30T09:00", "Friday next week", "next week on Friday", "next week Friday")
        assertReads("2026-10-23T14:00", "on Friday at 2")
        assertReads("2026-10-23T18:00", "Friday evening")
        assertReads("2026-10-18T09:00", "on Sunday")
        // Said on a Saturday after 9, "on Saturday" is a week on; with a time still ahead, today.
        assertReads("2026-10-24T09:00", "on Saturday")
        assertReads("2026-10-17T18:00", "on Saturday at 6")
    }

    @Test
    fun saidOnTheDayNextMeansAWeekOnAndTheWeekendMeansToday() {
        val saturdayMorning = at("2026-10-17T08:00")
        assertReads("2026-10-17T09:00", "on Saturday", "this weekend", anchor = saturdayMorning)
        assertReads("2026-10-24T09:00", "next Saturday", anchor = saturdayMorning)
        val sundayMorning = at("2026-10-18T08:00")
        assertReads("2026-10-18T09:00", "this weekend", anchor = sundayMorning)
        assertUnread("this weekend", anchor = at("2026-10-18T10:00"))
    }

    @Test
    fun nextWeekMonthAndYearAreTheSameDayAtNine() {
        assertReads("2026-10-24T09:00", "next week")
        assertReads("2026-11-17T09:00", "next month")
        assertReads("2027-10-17T09:00", "next year")
        assertReads("2028-02-29T09:00", "next month", anchor = at("2028-01-31T12:00"))
        assertReads("2026-10-26T15:00", "next week at 3", anchor = monday)
    }

    @Test
    fun weekendsAndMonthEnds() {
        assertReads("2026-10-24T09:00", "this weekend", "over the weekend", "this coming weekend", anchor = monday)
        assertReads("2026-10-23T09:00", "end of the week", "the end of this week", anchor = monday)
        assertReads("2026-10-31T09:00", "end of the month", "at the end of the month")
        assertUnread("this weekend")
        assertUnread("end of the month", anchor = at("2026-10-31T10:00"))
    }

    @Test
    fun datesAreTheNextOneComing() {
        assertReads("2026-10-20T09:00", "October 20th", "on October 20", "the 20th of October", "October twentieth", "on the 20th", "Oct 20", "Oct. 20th")
        assertReads("2026-11-15T09:00", "on the 15th", "on the 15th to pay the rent")
        assertReads("2026-10-31T09:00", "on the 31st")
        assertReads("2026-11-01T09:00", "the first of next month")
        assertReads("2027-10-16T09:00", "October 16th")
        assertReads("2026-10-20T15:00", "October 20th at 3", "oct 20th at 3")
        assertReads("2027-05-05T09:00", "may 5th", "on May 5", "the fifth of May")
        assertReads("2027-09-05T09:00", "Sept 5th")
        assertUnread("October 20th 2027")
    }

    @Test
    fun theDaylightSavingChangesAreHonored() {
        // 00:30 PDT on the fall-back day plus three hours is 02:30 PST, three real hours later.
        assertAfter(3 * 3600, "in 3 hours", anchor = at("2026-11-01T00:30"))
        assertReads("2026-11-01T09:00", "tomorrow at 9", anchor = at("2026-10-31T20:00"))
        // A day across the spring-forward change keeps the wall time, 23 real hours.
        assertReads("2027-03-14T22:00", "in a day", anchor = at("2027-03-13T22:00"))
    }

    @Test
    fun timesPastTheYearCapOrConflictingAreUnread() {
        assertUnread("in 2 years", "in 2 hours at 3", "in 1.5 weeks", "in 0 minutes", "tomorrow in 2 hours")
        // 1073741825 years is 12 months once the month count overflows an Int.
        assertUnread("in 1073741825.0 years")
        // 2027-10-17 to 2028-10-17 spans 2028-02-29: 366 days, past the cap.
        assertUnread("next year", anchor = at("2027-10-17T08:00"))
        assertUnread("at 5 on Friday and Saturday", "every Tuesday", "every day at 9", "on Mondays", "Friday after next")
    }

    @Test
    fun aStrayNumberNextToATimeAsksRatherThanBeingDropped() {
        assertUnread(
            "Remind me tomorrow 8 to call Sam",
            "Remind me seven thirty tomorrow to call Sam",
            "Remind me tomorrow 7.30 to call Sam",
            "Remind me Friday 3 to call Sam",
            "Remind me on Friday the 30th",
            "Remind me Monday the 26th",
            "Remind me tomorrow at, uh, 3",
            "Remind me at like 3",
            "Remind me in an hour 30",
            "Remind me at 3 or 4",
            "Remind me at 4, 30 people are coming",
            "Remind me 15 pm",
            "Remind me at 4 \"30 people are coming\"",
        )
    }

    @Test
    fun aNumberOfFiveDigitsOrMoreIsNotAnAmount() {
        assertUnread("in 10000 seconds")
    }

    @Test
    fun aNumberAfterAFillerOrACorrectionAsks() {
        assertUnread(
            "Remind me to call Sam at 3, uh, 30",
            "Remind me to call Sam, uh, tomorrow, uh, 3",
            "Remind me at 5, actually 6",
            "Remind me at 5, no, 6",
            "Remind me tomorrow at 3, sorry, 4",
            "Remind me at 3, I mean 4",
            "Remind me at 3. Actually, make it 4",
            "Remind me at 3. Actually, 4.",
            "Remind me at 3 or, um, 4",
            "Remind me at 3 or maybe 4",
            "Remind me at 3 and 4",
            "Remind me at 3 to call Sam, no, 4",
            "Remind me at 3, no, make that 4",
            "Remind me at 3, I meant 4",
            "Remind me at 3, scratch that, 4",
            "Remind me at 3, let's say 4",
            "Remind me at 3, or better 4",
            "Remind me at 3. Change that to 4",
            "Remind me at 5, wait, 6",
            "Remind me at 5, rather 6",
            "Remind me at 3, no, half past",
            "Remind me tomorrow, no, the day after",
            "Remind me at 5, no, later",
            "Remind me in 10 minutes, make that 20",
            "Remind me at 3, no, make it 4 instead",
            "Remind me at 3, no, 4 would be better",
            "Remind me at 3. Actually 4 works",
            "Remind me at 3, uh, 4 would be better",
            "Remind me at 3, never mind, 4",
            "Remind me at 3, on second thought 4",
            "Remind me at 3, my bad, 4",
            "Remind me at 3, instead 4",
            "Remind me at 3, strike that, 4",
            "Remind me at 3, I said 4",
            "Remind me at 3, or should I say 4",
            "Remind me at 3, nope, 4",
            "Remind me at 3, oops, 4",
            "Remind me at 3, correction, 4",
            "Remind me at 5, no, tomorrow",
            "Remind me on the 25th, no, 26th",
            "Remind me at 3, no, I think it should be 4",
            "Remind me at 3. Actually, could we push that to 4",
            "Remind me at 3. Actually, I think it would be better tomorrow",
            "Remind me at 7, I mean a.m.",
            "Remind me on Friday, no, the day before",
            "Remind me on the fifth, no, sixth",
            "Remind me at 3 to call Sam, no, 4 to call Sam",
            anchor = monday,
        )
        assertReads("2026-10-17T20:00", "Remind me at 8 to buy 2 apples and 3 pears", "Remind me at 8 to buy 2 apples, no, 3 apples")
        assertReads("2026-10-18T09:00", "Remind me to buy eggs tomorrow and 2 loaves of bread", "Remind me tomorrow to actually call Sam")
        assertReads("2026-10-18T15:00", "Remind me tomorrow to actually call Sam at 3")
        assertReads("2026-10-17T17:00", "Remind me, sorry, at 5 to call Sam")
        assertReads("2026-10-17T18:00", "Remind me at 6. Sorry, I had to call Sam about the hour of the meeting")
        assertReads("2026-10-18T09:00", "Remind me to buy 2 apples tomorrow, uh, 3 apples")
        assertReads("2026-10-18T15:00", "Remind me at 3, no, Sam can not come. The room is 4")
        assertUnread("Remind me at 7, sorry, p.m.", anchor = at("2026-10-19T05:00"))
        assertReads("2026-10-18T09:00", "Remind me tomorrow at 9. Actually I am going to call Sam", "Remind me tomorrow, sorry, it's his first day")
    }

    @Test
    fun durationsChainAcrossPunctuationOnlyWithAndAndARepeatCountsOnce() {
        assertAfter(300, "Remind me in five minutes, five minutes, to check the oven", "Remind me in five minutes five minutes")
        assertAfter(600, "Remind me in 10 minutes... 10 minutes to check the oven", "Remind me in 10 minutes. 10 minutes. Check the oven.")
        assertAfter(3900, "Remind me in an hour, and 5 minutes")
        assertAfter(3600, "Remind me in 60 minutes, 1 hour")
        assertUnread(
            "Remind me in an hour, an hour and a half",
            "Remind me in 20 minutes, 10 minutes before the meeting",
            "Remind me in 2 hours. 19 minutes later",
            "Remind me in 2 hours, 19 minutes",
            "Remind me in 5 minutes and 5 minutes",
            "Remind me in 5 minutes 10 minutes",
            "Remind me in 5 minutes, 5 hours",
            "Remind me in an hour, a quarter hour",
            "Remind me in 2 hours and, 19 minutes",
        )
    }

    @Test
    fun aCountBeforeAPartOfTheDayIsNotANightHour() {
        assertUnread("Remind me to take 2 in the morning", "Remind me to take 1 in the morning", "Remind me two thirty in the morning", "Remind me 5 in the morning")
    }

    @Test
    fun wordsBeforeATimeThatChangeItAsk() {
        assertUnread(
            "Remind me to finish the report by the end of next week",
            "Remind me at the end of next month",
            "Remind me at the start of next week",
            "Remind me what Sam said last Friday",
            "Remind me since Friday",
            "Remind me every other Friday",
            "Remind me Friday week",
            "Remind me every weekday at 7",
            "Remind me on weekdays at 7",
            "Remind me daily",
            "Remind me no later than 5pm",
            "Remind me about tomorrow's meeting at 9",
            "Remind me about tomorrow’s meeting at 9",
            "Remind me what Sam said the previous Friday",
            "Remind me what Sam said the past Friday",
            "Remind me at the end of May",
            "Remind me to book a table for Saturday",
            "Remind me to buy groceries for the weekend",
        )
        assertUnread(
            "Remind me late next week", "Remind me early next week", "Remind me mid next week", "Remind me later next week",
            "Remind me sometime next week", "Remind me early next month", "Remind me earlier next year", "Remind me in the middle of next week",
            "Remind me some time next week", "Remind me at some point next week", "Remind me next week sometime", "Remind me next week early",
            "Remind me late tomorrow", "Remind me tomorrow late", "Remind me late on Friday", "Remind me late in the evening",
            "Remind me during the weekend", "Remind me the first week of next month", "Remind me through the weekend",
            "Remind me to renew my passport early next year", "Remind me to pay the rent late next month", "Remind me to call Sam late next week",
            "Remind me to call Sam, late tomorrow", "Late tomorrow call Sam", "Remind us late tomorrow", "Remind myself late tomorrow",
            "Remind me the following day at 9", "Remind me to call Sam the following Friday",
            anchor = monday,
        )
        assertReads("2026-10-20T09:00", "Remind me to wake up early tomorrow", "Remind me tomorrow, late", anchor = monday)
        assertReads("2026-10-22T09:00", "Remind me to work late on Thursday", anchor = monday)
        assertReads("2026-10-26T09:00", "Remind me later, next week", anchor = monday)
        assertReads("2026-10-17T20:00", "Remind me later tonight")
        assertReads("2026-10-18T09:00", "Remind me sometime tomorrow")
    }

    @Test
    fun aRelationWordEarlierInTheClauseAsks() {
        assertUnread(
            "Remind me to prepare before the meeting at 3",
            "Remind me to print the slides before my meeting at 3pm",
            "Remind me to pack the night before my flight on Friday",
            "Remind me to set up before Sam arrives at 6",
            "Remind me to get gas ahead of the trip on Friday",
            "Remind me to submit it prior to the deadline on Friday",
            "Remind me in advance of Friday",
            "Remind me to call Sam after the game on Sunday",
            "Remind me to send the report by at 5",
            "Remind me to drop by tomorrow",
            "Remind me to come by 5 pm",
            "Remind me to come by Friday",
            "Remind me to get ready for the party at 8",
            "Remind me to leave in time for my flight at 6",
            "Remind me to call Sam once the meeting at 3 is over",
            "Remind me to call Sam following the meeting at 3",
            "Remind me to call the vet when they open at 9",
            "By Friday remind me to pay the rent",
            "Remind me to get the server running by the meeting at 3",
            "Remind me to have dinner going by the time Sam gets home at 6",
            "Remind me to come by the time Sam gets home at 6",
            "Remind me to leave in time to make the meeting at 3",
            "Remind me to call Sam as soon as the meeting at 3 is over",
            "Remind me to leave early for the meeting at 3",
            "Remind me not to be late for the meeting at 3",
            "Remind me to finish by check-in on Friday",
            "Remind me to leave with plenty of time for the meeting at 3",
            "Remind me on time for the meeting at 3",
            "Remind me tomorrow when work ends",
            "Remind me to call Sam tomorrow following lunch",
            anchor = monday,
        )
        // A "by" after a verb of passing, or a relation inside a read span or in an earlier sentence, places nothing.
        assertReads(
            "2026-10-18T09:00",
            "Remind me to stop by the store tomorrow", "Remind me to go by the bank tomorrow", "Remind me to pay by card tomorrow",
            "Remind me to look after the dog tomorrow", "Remind me to plan ahead tomorrow", "Remind me to look for my keys tomorrow",
            "Remind me to check prior emails tomorrow", "Remind me to pay in advance tomorrow", "Remind me to run by the post office tomorrow",
            "Remind me to call Sam by phone tomorrow", "Remind me to call Sam once more tomorrow", "Remind me to do the following tomorrow",
            "Remind me to pay the rent by check tomorrow", "Remind me as soon as possible tomorrow",
        )
        assertReads("2026-10-23T09:00", "Remind me to call Sam once again on Friday")
        assertReads("2026-10-23T09:00", "Remind me about following up with Sam on Friday")
        assertReads("2026-10-17T20:00", "Remind me it's time for my pills at 8 pm")
        assertReads("2026-10-17T18:00", "Remind me once it's 6 pm to call Sam", "Remind me if it's 6 pm")
        assertReads("2026-10-23T09:00", "Remind me to stop by on Friday")
        assertReads("2026-10-17T17:00", "Remind me when it's 5 pm", "Remind me when it is 5 pm")
        assertAfter(300, "Remind me to look for my keys in 5 minutes")
        assertReads("2026-10-19T15:00", "Remind me the day after tomorrow at 3")
        assertReads("2026-10-17T17:00", "Remind me before I forget. Call Sam at 5")
    }

    @Test
    fun aTimeThatDescribesSomethingElseAsks() {
        assertUnread(
            "Remind me to catch the 5:15 train",
            "Remind me to buy 2 tickets for the 7:30 show",
            "Remind me to buy the Sunday paper",
            "Remind me about my Friday meeting",
            "Remind me about my Friday morning run",
            "Remind me to cancel the 5:15",
            "Remind me to catch the 5:15 to work",
            "Remind me to reschedule my 3pm",
            "Remind me to skip those 5 o'clock meetings",
            "Remind me to cancel all Friday meetings",
            "Remind me about Sam's 3pm call",
            "Remind me the meeting's at 3",
            "Remind me the meeting is now at 3",
            "Remind me the store closes at 9",
            "Remind me my flight leaves at 6",
            "Remind me about that 3pm call",
            "Remind me to cancel this 5 pm meeting",
            "Remind me to prep her 9 o'clock appointment",
            "Remind me to cancel these Friday meetings",
            "Remind me to set a 7 am alarm",
            "Remind me to prep our 3pm",
            "Remind me the meeting starts at 3",
            "Remind me the report is due tomorrow at 5",
            "Remind me to order Domino's at 6",
            "Remind me the meeting's tomorrow at 3",
            "Remind me about Sam's Friday meeting",
            "Remind me to drive to my mom's 3pm appointment",
            "Remind me about Sam's three o'clock call",
            "Remind me about that noon call",
            "Remind me to file it mid next week",
            "Remind me to take my 8 o'clock pill",
            "Remind me to tell Sam the meeting is on Friday",
            "Ask Sam if the party is tomorrow",
            "Remind me that it's at 8",
            "Remind me they're on Friday",
        )
        assertReads("2026-10-17T17:15", "Remind me 5:15 to call Sam", "5:15 call Sam", "Remind me to call Sam 5:15")
        assertReads("2026-10-18T17:15", "Remind me tomorrow 5:15 to call Sam")
        assertReads("2026-10-23T09:00", "Remind me to go to Sam's on Friday", "Remind me to go to the doctor's on Friday")
        assertReads(
            "2026-10-18T09:00",
            "Remind me to call her tomorrow regarding the car", "Remind me to send that tomorrow", "Remind me that tomorrow Sam needs a ride",
            "Remind me to send those tomorrow", "Remind me to bring them all tomorrow", "Remind me to call McDonald's tomorrow",
        )
        assertReads("2026-10-18T09:00", "Remind me to go to my mom's on Sunday", "Remind me to visit mom's on Sunday", "Remind me to go to her mom's on Sunday", "Remind me about visiting mom's on Sunday")
        assertReads("2026-10-17T17:00", "Remind me to stop at Sam's at 5", "Remind me to pick up dinner from Joe's at 5")
        assertReads("2026-10-18T09:00", "Remind me how it is. Tomorrow call Sam")
    }

    @Test
    fun timesTheRulesDoNotCountOutAsk() {
        assertUnread(
            "Remind me at a quarter to midnight",
            "Remind me at half past noon",
            "Remind me at quarter past midnight",
            "Remind me half 5",
            "Remind me ten to one to call Sam",
            "Remind me within the hour",
            "Remind me by end of day",
            "Remind me at the end of the day",
            "Remind me between 3 and 4",
            "Remind me at 24:00",
            "Remind me to pay the bill by the 5th",
            "Remind me the 5th to call Sam",
            "Remind me at half to 4",
        )
    }

    @Test
    fun anHourFollowedByAPlainWordAsks() {
        assertUnread("Remind me to look at 3 apartments on Saturday", "Remind me the sale is at 10 percent off", "Remind me to meet at 5 Elm Street")
    }

    @Test
    fun timeWordsTheRulesCannotReadAreUnread() {
        assertUnread(
            "Remind me to call Sam after lunch",
            "Remind me to call Sam during lunch",
            "Remind me to call Sam when I get home",
            "Remind me to take my pills before bed",
            "Remind me to call Sam tomorrow after lunch",
            "Remind me in a couple of hours",
            "Remind me in a few minutes",
            "Remind me in a hundred minutes",
            "Remind me to submit it by Friday",
            "Remind me before 3pm",
            "Remind me this week",
            "Remind me next weekend",
            "Remind me on Christmas",
            "Remind me in May",
            "Remind me in 10",
            "Remind me every morning",
            "Remind me to call Sam once I'm home",
            "Remind me as soon as I get home",
            "Remind me to buy 10 to 12 apples",
        )
    }

    @Test
    fun vagueOrNoTimeWordsAreNone() {
        assertNone(
            "Remind me to call Sam",
            "Remind me to call Sam later",
            "Remind me to call Sam soon",
            "Remind me in a bit to call Sam",
            "Remind me to call Sam later today",
            "Remind me to buy 2 apples",
            "Remind me that I am meeting Sam",
            "Remind me to plan my day",
            "Remind me to set my morning alarm",
            "Remind me about Friday's party",
            "Remind me about happy hour",
            "Remind me to read the meeting minutes",
            "Remind me to stop by the store",
            "Remind me to drop off the package on the 3rd floor",
            "Remind me to put the box on the second shelf",
            "Remind me that Sam lives on the 4th floor",
            "Remind me to take a second look at the report",
            "Remind me to call April",
            "Remind me to call Sam at work",
            "Remind me to pay 7.50 for parking",
            "Remind me to book the later flight",
        )
    }

    @Test
    fun plainWordsNextToATimeDoNotSpoilIt() {
        assertReads("2026-10-18T15:00", "Remind me that I am meeting Sam tomorrow at 3")
        assertReads("2026-10-18T09:00", "Remind me tomorrow to set my morning alarm", "Remind me that I may be late tomorrow")
        assertReads("2026-10-17T20:00", "Remind me at 8 to buy 2 apples")
        assertReads("2026-10-18T09:00", "Remind me to buy 2 apples tomorrow")
        assertReads("2026-10-17T17:00", "Remind me to stop by at 5", "Remind me to call April at 5")
    }

    @Test
    fun whatTheKnownIssueSaysOfOtherLanguagesMisreadsAndFalseAsks() {
        assertReads("2026-10-17T17:00", "Recuérdame a las 5 pm")
        assertNone("Rappelle-moi demain")
        assertReads("2026-10-17T19:11", "Remind me to stop at 7-Eleven")
        assertReads("2026-10-17T21:00", "Remind me I parked at 9")
        assertNone("Remind me to call Sam first thing", "Remind me at sunset to close the blinds")
        assertUnread("Remind me to ask Sam when the party is")
    }

    @Test
    fun aRecordingJustBeforeMidnight() {
        val late = at("2026-10-17T23:59:30")
        assertReads("2026-10-18T00:00", "at midnight", anchor = late)
        assertReads("2026-10-18T09:00", "tomorrow", anchor = late)
        assertAfter(60, "in 1 minute", anchor = late)
    }

    @Test
    fun timeWordsAreTakenOutOfAMessage() {
        val cases = mapOf(
            "Feed the dogs in five minutes" to "Feed the dogs",
            "Take out the trash in one hour and five minutes" to "Take out the trash",
            "Feed the dogs tomorrow at 10am" to "Feed the dogs",
            "Tomorrow, call Sam" to "Call Sam",
            "Feed the dogs tomorrow at noon" to "Feed the dogs",
            "Take out trash in 2 hours 19 minutes" to "Take out trash",
            "Water the plants this weekend" to "Water the plants",
            "Call mom on Friday at 3" to "Call mom",
            "Call Sam later" to "Call Sam",
            "Water the plants in two weeks' time" to "Water the plants",
            "Call Sam (tomorrow)" to "Call Sam",
            "Call Sam {tomorrow}" to "Call Sam",
            "Call Sam \"tomorrow\"" to "Call Sam",
            "\"Call\" \"Sam\" tomorrow" to "\"Call\" \"Sam\"",
            "Call Sam - 5pm - about the car" to "Call Sam - about the car",
            "Call Sam tomorrow about the car" to "Call Sam about the car",
            "Call Sam Friday evening" to "Call Sam",
            "Feed the dogs tomorrow 10am" to "Feed the dogs",
            "Call Sam tomorrow, he has the keys" to "Call Sam, he has the keys",
            "Call Sam, tomorrow he has the keys" to "Call Sam, he has the keys",
            "Call Sam “tomorrow”" to "Call Sam",
            "Meet Sam at 5 at the cafe" to "Meet Sam at the cafe",
            "Call Sam at 5 if he is free" to "Call Sam if he is free",
            "Call Sam tomorrow regarding the car" to "Call Sam regarding the car",
            "Take Monday as vacation" to "Take Monday as vacation",
            "Call Sam - tomorrow" to "Call Sam",
            "Call Sam, tomorrow." to "Call Sam.",
            "Call Sam" to "Call Sam",
            "Set my morning alarm" to "Set my morning alarm",
            "Drop off the package on the 3rd floor" to "Drop off the package on the 3rd floor",
        )
        for ((message, expected) in cases) assertEquals(expected, SpokenTimeReader.withoutTimeWords(message), message)
    }

    @Test
    fun aMessageThatWouldNotStayCleanIsKeptAsItIs() {
        for (message in listOf(
            "tomorrow", "Tomorrow.", "Tomorrow at 5.", "In 5 minutes!", "Finish the report before Friday",
            "Water the plants every Friday", "Wait until Friday", "Pack for the weekend", "Remind me as soon as I get home",
            "Book the later flight", "Call Sam on Friday the 30th",
            "Watch Friday Night Lights", "Call Sam at 5 o'clock sharp",
            "Tell Sam the meeting is on Friday", "Take 2 in the morning",
        )) {
            assertEquals(message, SpokenTimeReader.withoutTimeWords(message), message)
        }
    }
}
