package com.anopticlabs.gravel.transcription

/** Whether a dictation holds the speech engine; other on-device inference gives way while one does. */
interface DictationPriority {
    val isHeld: Boolean

    suspend fun awaitRaised()

    suspend fun awaitReleased()
}
