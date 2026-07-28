package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.weather.WeatherDailyForecast
import io.rebble.libpebblecommon.weather.WeatherType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * allStringsLength must count what SLongString actually writes (UTF-8 bytes plus the
 * 2-byte per-string headers). Counting String.length (UTF-16 code units) understated
 * the block for non-ASCII names, corrupting the firmware's record-length accounting.
 */
internal class WeatherAppBlobRecordStringLengthTest {

    // "Zürich" is 6 UTF-16 code units but 7 UTF-8 bytes; "10°" is 3 units, 4 bytes.
    private val locationName = "Zürich"
    private val forecastShort = "10°"
    private val expectedStringsBytes = 7 + 2 + 4 + 2

    private fun declaredLengthAt(bytes: UByteArray, offset: Int): Int =
        bytes[offset].toInt() or (bytes[offset + 1].toInt() shl 8)

    @Test
    fun v4RecordCountsUtf8Bytes() {
        val record = WeatherAppBlobRecordV4(
            currentTemp = 18,
            currentWeatherType = 7u,
            todayHighTemp = 21,
            todayLowTemp = 13,
            tomorrowWeatherType = 0u,
            tomorrowHighTemp = 18,
            tomorrowLowTemp = 11,
            lastUpdateTimeUtc = 1750000000u,
            isCurrentLocation = false,
            todayFeelsLikeTemp = WEATHER_V4_TEMP_UNKNOWN,
            todayUvIndexX10 = WEATHER_V4_UV_UNKNOWN,
            todayPrecipProbability = WEATHER_V4_PRECIP_UNKNOWN,
            todayWindSpeed = WEATHER_V4_WIND_SPEED_UNKNOWN,
            todayWindDirection = WEATHER_V4_WIND_DIR_UNKNOWN,
            latitudeE2 = WEATHER_V4_COORD_UNKNOWN,
            longitudeE2 = WEATHER_V4_COORD_UNKNOWN,
            numDaily = 1u,
            daily = listOf(WeatherDailyForecast(21, 13, WeatherType.Sun)),
            todayHourlyCount = 0u,
            todayHourlyWeatherType = UByteArray(24) { WeatherType.Unknown.code.toUByte() },
            todayHourlyTemp = ByteArray(24),
            locationUtcOffsetMin = WEATHER_V4_UTC_OFFSET_UNKNOWN,
            todayWindDirDeg = WEATHER_V4_WIND_DIR_DEG_UNKNOWN,
            locationName = locationName,
            forecastShort = forecastShort,
        )

        val bytes = record.toBytes()

        // Fixed block is 177 bytes (see WeatherAppBlobRecordV4Test), then the declared
        // length, then the string block itself.
        assertEquals(177 + 2 + expectedStringsBytes, bytes.size)
        assertEquals(expectedStringsBytes, declaredLengthAt(bytes, 177))
    }

    @Test
    fun v3RecordCountsUtf8Bytes() {
        val record = WeatherAppBlobRecord(
            currentTemp = 18,
            currentWeatherType = 7u,
            todayHighTemp = 21,
            todayLowTemp = 13,
            tomorrowWeatherType = 0u,
            tomorrowHighTemp = 18,
            tomorrowLowTemp = 11,
            lastUpdateTimeUtc = 1750000000u,
            isCurrentLocation = false,
            locationName = locationName,
            forecastShort = forecastShort,
        )

        val bytes = record.toBytes()

        // The declared length sits immediately before the string block at the record tail.
        val declaredOffset = bytes.size - expectedStringsBytes - 2
        assertEquals(expectedStringsBytes, declaredLengthAt(bytes, declaredOffset))
    }
}
