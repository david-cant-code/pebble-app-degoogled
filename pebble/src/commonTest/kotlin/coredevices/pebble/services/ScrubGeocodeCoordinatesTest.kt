package coredevices.pebble.services

import coredevices.pebble.weather.formatCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrubGeocodeCoordinatesTest {

    private val scrubbed =
        "https://weather-api.repebble.com/api/v1/geocode/xx.xxxxxx/yy.yyyyyy?language=en&units=m"

    private fun url(latitude: String, longitude: String) =
        "https://weather-api.repebble.com/api/v1/geocode/$latitude/$longitude?language=en&units=m"

    @Test
    fun scrubsFixedPointCoordinates() {
        assertEquals(scrubbed, url("37.756", "-122.419").scrubGeocodeCoordinates())
    }

    @Test
    fun scrubsRegardlessOfCoordinateRendering() {
        // Raw Double interpolation used to produce these shapes, which the old
        // digits-dot-digits pattern missed, leaking the precise pair to the log
        assertEquals(scrubbed, url("5.0E-4", "-0.1278").scrubGeocodeCoordinates())
        assertEquals(scrubbed, url("10", "0").scrubGeocodeCoordinates())
    }

    @Test
    fun scrubsEveryFormatCoordinateRendering() {
        for (value in listOf(0.0, 0.0005, -0.00001, 90.0, -180.0, 51.5074)) {
            assertEquals(
                scrubbed,
                url(formatCoordinate(value), formatCoordinate(value)).scrubGeocodeCoordinates(),
            )
        }
    }

    @Test
    fun leavesOtherUrlsAlone() {
        val other = "https://appstore-api.repebble.com/api/v1/apps/id/12.34"
        assertEquals(other, other.scrubGeocodeCoordinates())
    }
}
