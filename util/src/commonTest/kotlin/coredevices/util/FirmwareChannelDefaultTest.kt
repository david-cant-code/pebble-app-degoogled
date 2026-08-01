package coredevices.util

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Pins the firmware update channel default to Soaked (false): a fresh
 * install must never start on the Early tier, and configs persisted before
 * the field existed must keep decoding to the safe default instead of
 * failing (which would silently reset the whole stored config, see
 * CoreConfigHolder.loadFromStorage).
 */
class FirmwareChannelDefaultTest {
    // Same settings as the Json instance CoreConfigHolder decodes with.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun freshConfigDefaultsToSoaked() {
        assertFalse(CoreConfig().firmwareUpdatesEarlyChannel)
    }

    @Test
    fun configPersistedBeforeTheFieldExistedDecodesToSoaked() {
        val decoded = json.decodeFromString<CoreConfig>("""{"fetchWeather":false}""")
        assertFalse(decoded.firmwareUpdatesEarlyChannel)
        assertFalse(decoded.fetchWeather)
    }
}
