package com.anopticlabs.gravel.notebook.storage

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * One process-wide mutex per Notebook store file, keyed by file name, shared by every store
 * instance in the process. Not reentrant: a method holding one never calls another that takes it.
 */
object NotebookLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun lockFor(name: String): Mutex = locks.computeIfAbsent(name) { Mutex() }
}
