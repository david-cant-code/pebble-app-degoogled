package com.anopticlabs.gravel.weather

/**
 * Whether the phone's position may be used right now: Gravel has location permission and the
 * phone's location switch is on. Bound on Android only.
 */
fun interface LocationAccess {
    suspend fun allowed(): Boolean
}
