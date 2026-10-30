package com.anopticlabs.gravel.weather

import coredevices.pebble.weather.migrateUnits
import coredevices.util.WeatherUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class UnitsMigrationTest {
    private suspend fun calls(chosen: WeatherUnit?, localeDefault: WeatherUnit): List<String> {
        val calls = mutableListOf<String>()
        migrateUnits(
            chosen = chosen,
            localeDefault = localeDefault,
            overwrite = { calls += "overwrite imperial=$it" },
            seed = { calls += "seed imperial=$it" },
            setWindMph = { calls += "wind mph" },
        )
        return calls
    }

    @Test
    fun aPickedWeatherUnitOverwritesTheStoredUnits() = runTest {
        WeatherUnit.entries.forEach { locale ->
            assertEquals(listOf("overwrite imperial=true"), calls(WeatherUnit.Imperial, locale))
            assertEquals(listOf("overwrite imperial=false"), calls(WeatherUnit.Metric, locale))
            assertEquals(listOf("overwrite imperial=false", "wind mph"), calls(WeatherUnit.UkHybrid, locale))
        }
    }

    @Test
    fun withNoPickTheLocaleUnitIsOnlySeeded() = runTest {
        assertEquals(listOf("seed imperial=true"), calls(null, WeatherUnit.Imperial))
        assertEquals(listOf("seed imperial=false"), calls(null, WeatherUnit.Metric))
        assertEquals(listOf("seed imperial=false", "wind mph"), calls(null, WeatherUnit.UkHybrid))
    }
}
