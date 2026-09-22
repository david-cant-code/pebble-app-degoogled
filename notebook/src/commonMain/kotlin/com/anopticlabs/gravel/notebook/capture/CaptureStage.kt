package com.anopticlabs.gravel.notebook.capture

/** The step a capture is at while it is processed; [label] is what the row shows. */
enum class CaptureStage(val label: String) {
    TRANSCRIBING("Transcribing"),
    THINKING("Thinking"),
    CREATING_NOTE("Writing your note"),
    SETTING_REMINDER("Setting your reminder"),
    COMPLETING_TASK("Assessing task completion"),
}

/** The stage to show once a transcript is classified; a recognized completion wins over the note/reminder fork. */
fun captureStageForClassify(isCompletion: Boolean, isReminder: Boolean): CaptureStage = when {
    isCompletion -> CaptureStage.COMPLETING_TASK
    isReminder -> CaptureStage.SETTING_REMINDER
    else -> CaptureStage.CREATING_NOTE
}
