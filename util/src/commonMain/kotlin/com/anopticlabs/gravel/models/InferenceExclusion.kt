package com.anopticlabs.gravel.models

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Held by a text generation and by the speech speed probe, so the probe never times the CPU under a generation. */
class InferenceExclusion {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
