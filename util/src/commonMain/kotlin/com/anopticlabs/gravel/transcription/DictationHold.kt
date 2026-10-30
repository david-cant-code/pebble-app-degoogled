package com.anopticlabs.gravel.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Keeps long-form transcription off the engine while a dictation may
 * need it: held while any hold is up. A hold ends when released,
 * superseded by a dictation's hold, or [bound] after its raise, for a
 * session that never transcribes locally.
 */
internal class DictationHold(private val scope: CoroutineScope, private val bound: Duration) : DictationPriority {

    class Hold internal constructor()

    private val held = MutableStateFlow<Set<Hold>>(emptySet())

    override val isHeld: Boolean get() = held.value.isNotEmpty()

    fun raise(): Hold {
        val hold = Hold()
        held.update { it + hold }
        scope.launch {
            delay(bound)
            release(hold)
        }
        return hold
    }

    /** A dictation's hold, which ends the holds raised before it: their sessions' dictation is this one or done. */
    fun supersede(): Hold {
        val hold = Hold()
        held.value = setOf(hold)
        scope.launch {
            delay(bound)
            release(hold)
        }
        return hold
    }

    fun release(hold: Hold) {
        held.update { it - hold }
    }

    /** Ends every hold without raising one, so nothing waiting for a raise sees one. */
    fun clear() {
        held.value = emptySet()
    }

    override suspend fun awaitRaised() {
        held.first { it.isNotEmpty() }
    }

    override suspend fun awaitReleased() {
        held.first { it.isEmpty() }
    }
}
