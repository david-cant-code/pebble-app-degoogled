package coredevices.util

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun explicitWeatherUnitsSurvivesRoundTrip() {
        WeatherUnit.entries.forEach { unit ->
            val encoded = json.encodeToString(CoreConfig(weatherUnits = unit))
            val decoded = json.decodeFromString<CoreConfig>(encoded)
            assertEquals(unit, decoded.weatherUnits)
            assertEquals(unit, decoded.resolvedWeatherUnits)
        }
    }

    @Test
    fun sttDebugHooksDefaultOffForExistingConfigs() {
        // A persisted config predating the hooks carries no such fields;
        // both must read as off, the only state a release build honours.
        val decoded = json.decodeFromString<CoreConfig>("""{"sttConfig":{"mode":"LocalOnly"}}""")
        assertEquals(false, decoded.sttConfig.debugSingleThread)
        assertEquals(false, decoded.sttConfig.debugCaptureDump)
        assertEquals(false, decoded.sttConfig.debugSubstituteAudio)
        assertEquals(false, decoded.sttConfig.debugSlowDecode)
    }

    @Test
    fun unsetWeatherUnitsFallsBackToDeviceDefault() {
        val decoded = json.decodeFromString<CoreConfig>("{}")
        assertEquals(null, decoded.weatherUnits)
        assertEquals(deviceDefaultWeatherUnit(), decoded.resolvedWeatherUnits)
    }
}
