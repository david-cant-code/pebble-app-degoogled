package com.anopticlabs.gravel.notebook.alarms

import com.anopticlabs.gravel.notebook.reminders.ReminderEvent
import com.anopticlabs.gravel.notebook.reminders.ReminderEventKey
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderSettings

internal class FakeScheduler : ReminderAlarmScheduler {
    val fires: MutableMap<Int, ReminderItem> = java.util.Collections.synchronizedMap(mutableMapOf())
    val snoozes: MutableMap<ReminderEventKey, Long> = java.util.Collections.synchronizedMap(mutableMapOf())

    /** Called with the item before a fire alarm is set, on the setting thread; a test can block there. */
    @Volatile var beforeScheduleFire: ((ReminderItem) -> Unit)? = null

    override fun scheduleFire(item: ReminderItem) {
        beforeScheduleFire?.invoke(item)
        fires[item.id] = item
    }

    /** Every id a fire alarm was cancelled for, in order. */
    val cancelledFires: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

    override fun cancelFire(id: Int) {
        cancelledFires += id
        fires.remove(id)
    }

    override fun scheduleSnooze(key: ReminderEventKey, message: String, fireAtMillis: Long) {
        snoozes[key] = fireAtMillis
    }

    override fun cancelSnooze(key: ReminderEventKey) {
        snoozes.remove(key)
    }

    @Volatile var lapseSweepAt: Long? = null

    override fun scheduleLapseSweep(atMillis: Long) {
        lapseSweepAt = atMillis
    }

    override fun cancelLapseSweep() {
        lapseSweepAt = null
    }
}

internal class FakeNotifier(var deliverable: Boolean = true) : ReminderNotifier {
    val posted = mutableListOf<Pair<ReminderEventKey, String>>()
    val live = mutableSetOf<ReminderEventKey>()
    val missedNotices = mutableListOf<List<ReminderEvent>>()
    /** The set notices showing, by reminder id; a post replaces the reminder's one, as the real notifier's tag and id do. */
    val setNotices = linkedMapOf<Int, ReminderItem>()
    @Volatile var cancelSetNoticeThrows = false
    /** Reminder ids whose set notice cancel throws, for a test that needs a later cancel to succeed. */
    val cancelSetNoticeFailsFor = mutableSetOf<Int>()
    @Volatile var cancelFireThrows = false
    @Volatile var postedSetNoticesThrows = false
    var completionsCount: Int? = null

    override fun postFire(key: ReminderEventKey, message: String): Boolean {
        posted += key to message
        if (deliverable) live += key
        return deliverable
    }

    override fun cancelFire(key: ReminderEventKey) {
        if (cancelFireThrows) throw IllegalStateException("notification service gone")
        live -= key
    }

    override fun cancelFires(id: Int) {
        if (cancelFireThrows) throw IllegalStateException("notification service gone")
        live.removeAll { it.reminderId == id }
    }

    override fun postedFires(): List<ReminderEventKey> = live.toList()

    override fun postMissedWhileOff(missed: List<ReminderEvent>) {
        if (missed.isNotEmpty()) missedNotices += missed
    }

    override fun postSetNotice(item: ReminderItem) {
        setNotices[item.id] = item
    }

    override fun cancelSetNotice(id: Int) {
        if (cancelSetNoticeThrows || id in cancelSetNoticeFailsFor) throw IllegalStateException("notification service gone")
        setNotices.remove(id)
    }

    override fun postedSetNotices(): List<PostedSetNotice> {
        if (postedSetNoticesThrows) throw IllegalStateException("notification service gone")
        return setNotices.values.map { PostedSetNotice(it.id, it.timeToCheck) }
    }

    override fun postCompletionsToReview(count: Int) {
        completionsCount = count
    }

    override fun cancelCompletionsToReview() {
        completionsCount = null
    }
}

internal class FakeSettings(
    override var swipeSnoozeMinutes: Int = 10,
    override var historyRetentionDays: Int = 90,
) : ReminderSettings
