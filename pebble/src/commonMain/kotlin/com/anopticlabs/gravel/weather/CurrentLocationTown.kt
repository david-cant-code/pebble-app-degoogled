package com.anopticlabs.gravel.weather

import com.anopticlabs.gravel.places.PlaceTable
import com.anopticlabs.gravel.places.Town
import coredevices.database.WeatherLocationEntity
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import kotlinx.coroutines.CancellationException

/** The name a Current Location row has before any position is known, and after it is forgotten. */
const val CURRENT_LOCATION_NAME = "Current Location"

/** The Current Location row with no position and its default name. */
fun forgottenCurrentLocation(record: WeatherLocationEntity): WeatherLocationEntity =
    record.copy(latitude = null, longitude = null, name = CURRENT_LOCATION_NAME)

/** How far from the phone a more populous listed place is sent instead of the nearest one. */
const val CURRENT_LOCATION_RADIUS_KM = 10.0

/**
 * The town Current Location is sent as for a phone at the position: the most populous listed
 * place within [CURRENT_LOCATION_RADIUS_KM], or the nearest listed place when none is that close.
 */
fun PlaceTable.currentLocationTown(latitude: Double, longitude: Double): Town? =
    largestWithin(latitude, longitude, CURRENT_LOCATION_RADIUS_KM)

/** Whether a listed place sits exactly at the position, as at a town [currentLocationTown] picked. */
fun PlaceTable.hasTownAt(latitude: Double, longitude: Double): Boolean =
    nearest(latitude, longitude)?.let { it.latitude == latitude && it.longitude == longitude } == true

/**
 * The Current Location row as it may be stored and sent: [townNear] the phone's position; with no
 * position from the phone, a stored town ([isTown]) as it is and any other stored position moved
 * to [townNear] it. With location not [allowed], or no town to name, the row keeps no position, so
 * nothing is sent for it. [position] is only asked for when location is allowed.
 */
suspend fun currentLocationAsTown(
    record: WeatherLocationEntity,
    allowed: Boolean,
    position: suspend () -> GeolocationPositionResult,
    townNear: suspend (latitude: Double, longitude: Double) -> Town?,
    isTown: suspend (latitude: Double, longitude: Double) -> Boolean,
): WeatherLocationEntity {
    val forgotten = forgottenCurrentLocation(record)
    if (!allowed) return forgotten
    val fix = position()
    val town = try {
        when (fix) {
            is GeolocationPositionResult.Success -> townNear(fix.latitude, fix.longitude)
            is GeolocationPositionResult.Error -> {
                val latitude = record.latitude ?: return record
                val longitude = record.longitude ?: return record
                if (isTown(latitude, longitude)) return record
                townNear(latitude, longitude)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return forgotten
    return record.copy(latitude = town.latitude, longitude = town.longitude, name = town.name)
}
