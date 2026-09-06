package coredevices.util

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun selfHostedServerIsUnsetForExistingConfigs() {
        val decoded = json.decodeFromString<CoreConfig>("""{"sttConfig":{"mode":"LocalOnly"}}""")
        assertEquals(null, decoded.sttConfig.serverUrl)
        assertEquals(null, decoded.sttConfig.serverModel)
    }

    @Test
    fun sttConfigNeverPrintsTheServerUrl() {
        val printed = STTConfig(serverUrl = "https://stt.example.net:8443/inference", serverModel = "whisper-1").toString()
        assertFalse(printed.contains("example.net"), printed)
        assertFalse(printed.contains("8443"), printed)
        assertTrue(printed.contains("serverUrl=[set]"), printed)
        assertTrue(printed.contains("mode=LocalOnly") && printed.contains("serverModel=whisper-1"), printed)
        assertTrue(STTConfig().toString().contains("serverUrl=null"))
    }

    @Test
    fun sttConfigPrintsEveryFieldItDeclares() {
        // The redacting toString names its fields by hand, so one added to
        // the class has to be added there too; the serializer's descriptor
        // lists what the class declares.
        val printed = STTConfig().toString()
        val descriptor = STTConfig.serializer().descriptor
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            assertTrue(printed.contains("$name="), "toString leaves out $name: $printed")
        }
    }

    @Test
    fun unsetWeatherUnitsFallsBackToDeviceDefault() {
        val decoded = json.decodeFromString<CoreConfig>("{}")
        assertEquals(null, decoded.weatherUnits)
        assertEquals(deviceDefaultWeatherUnit(), decoded.resolvedWeatherUnits)
    }
}
