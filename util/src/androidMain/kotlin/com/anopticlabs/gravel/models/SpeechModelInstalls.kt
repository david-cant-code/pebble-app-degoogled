package com.anopticlabs.gravel.models

import coredevices.util.models.laneOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gravel: counts the speech model downloads the download job has finished in this process. It takes no
 * ModelDownloadManager, so the watch that follows it from app start builds none: the manager's build reads the
 * system's list of scheduled jobs.
 */
class SpeechModelInstalls {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun downloaded(slug: String) {
        if (laneOf(slug) == ModelRole.Speech) _count.update { it + 1 }
    }
}
