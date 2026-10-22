package com.anopticlabs.gravel.ui

/** The values Settings > Notebook offers for its reminder settings. */
object NotebookSettingsChoices {
    val swipeSnoozeMinutes = listOf(5, 10, 15, 30, 60, 120)
    val quickRemindMinutes = listOf(15, 30, 60, 120, 240)

    /** Zero, keep forever, is listed last. */
    val historyRetentionDays = listOf(30, 90, 180, 365, 0)

    /** [choices] with [current] added when it is not one of them, so the row can show it; forever stays last. */
    fun withCurrent(choices: List<Int>, current: Int): List<Int> =
        if (current in choices) choices else (choices + current).sortedWith(compareBy({ it == 0 }, { it }))

    fun minutesText(minutes: Int): String = when {
        minutes % 60 != 0 -> if (minutes == 1) "1 minute" else "$minutes minutes"
        minutes == 60 -> "1 hour"
        else -> "${minutes / 60} hours"
    }

    fun daysText(days: Int): String = when (days) {
        0 -> "Forever"
        365 -> "1 year"
        1 -> "1 day"
        else -> "$days days"
    }
}

/** Descriptions Settings > Notebook and the setup guide share, without a closing period as Settings shows them. */
object NotebookSettingsText {
    const val INTERRUPT_LONG =
        "A recording of 30 seconds or less stops a long one that is being transcribed. The long one starts over afterward, which uses more battery"
    const val WAIT_FOR_POWER =
        "Recordings over 30 seconds that this phone transcribes start only while it is plugged in. The Notebook tab offers Process now"
    const val QUICK_REMIND = "A voice reminder with no clear time is set to this default time from when it was processed"
    const val AUTO_BACKUPS = "Back up your notes and reminders to a folder you choose, sealed with a recovery key you keep from setup"
    const val SET_UP_AGAIN =
        "Gravel can't read its automatic backup settings or key. Setting up again picks a folder and makes a new recovery key; " +
            "backups already in your folder stay there and still open with your old key"
}
