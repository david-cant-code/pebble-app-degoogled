package com.anopticlabs.gravel.models

/** Gravel: told by the download job when a download of either role has finished; the job waits for [finished]. */
interface TextDownloadFollower {
    suspend fun finished(modelId: String)
}
