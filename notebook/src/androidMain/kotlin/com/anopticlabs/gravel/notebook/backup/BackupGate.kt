package com.anopticlabs.gravel.notebook.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One backup run over the Notebook at a time: a manual backup while it builds, a manual restore,
 * and an automatic run. The app holds one per process.
 */
class BackupGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Takes the gate only when it is free; the caller then calls [unlock]. */
    fun tryLock(): Boolean = mutex.tryLock()

    fun unlock() = mutex.unlock()
}
