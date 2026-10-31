package com.anopticlabs.gravel.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * A paired ring whose Devices entry shows [statusLine] in place of upstream's "Ready", and offers [importAgain]
 * while [canImportAgain] says syncing is stopped on a damaged saved position.
 */
interface RingSyncStatus {
    val statusLine: StateFlow<String>
    val canImportAgain: StateFlow<Boolean>

    suspend fun importAgain()
}
