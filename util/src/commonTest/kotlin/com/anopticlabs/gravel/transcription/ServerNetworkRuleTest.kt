package com.anopticlabs.gravel.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.STTConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerNetworkRuleTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun storedConfig(sttJson: String) = MapSettings("coreapp.config" to """{"sttConfig":$sttJson}""")

    private fun holder(settings: MapSettings) = CoreConfigHolder(defaultValue = CoreConfig(), settings = settings, json = json)

    @Test
    fun aServerSetUpBeforeTheUpdateKeepsMobileData() {
        val settings = storedConfig("""{"mode":"RemoteOnly","serverUrl":" https://stt.example.net/inference "}""")
        settleServerOnMobileData(holder(settings), settings)
        assertEquals(true, holder(settings).config.value.sttConfig.serverOnMobileData, "read back after a restart")
    }

    @Test
    fun withoutAServerALaterSetupStartsOnWifiOnly() {
        val settings = storedConfig("""{"mode":"LocalOnly"}""")
        settleServerOnMobileData(holder(settings), settings)
        val restarted = holder(settings)
        assertEquals(false, restarted.config.value.sttConfig.serverOnMobileData)

        val config = restarted.config.value
        restarted.update(config.copy(sttConfig = config.sttConfig.copy(serverUrl = "https://stt.example.net/inference")))
        settleServerOnMobileData(holder(settings), settings)
        assertEquals(false, holder(settings).config.value.sttConfig.serverOnMobileData)
    }

    @Test
    fun aUrlTheAppWouldNotUseIsNoServer() {
        val settings = storedConfig("""{"serverUrl":"http://stt.example.net/inference"}""")
        settleServerOnMobileData(holder(settings), settings)
        assertEquals(false, holder(settings).config.value.sttConfig.serverOnMobileData)
    }

    @Test
    fun aConfigRebuiltAfterTheFirstRunIsNotSettledAgain() {
        val settings = storedConfig("""{"mode":"LocalOnly"}""")
        settleServerOnMobileData(holder(settings), settings)
        // Code elsewhere may write a fresh STTConfig, which carries the switch's default.
        holder(settings).update(CoreConfig(sttConfig = STTConfig(serverUrl = "https://stt.example.net/inference")))
        settleServerOnMobileData(holder(settings), settings)
        assertEquals(false, holder(settings).config.value.sttConfig.serverOnMobileData)
    }

    @Test
    fun aSwitchTheUserTurnedOffStaysOff() {
        val settings = storedConfig("""{"mode":"RemoteOnly","serverUrl":"https://stt.example.net/inference"}""")
        settleServerOnMobileData(holder(settings), settings)
        val config = holder(settings).config.value
        holder(settings).update(config.copy(sttConfig = config.sttConfig.copy(serverOnMobileData = false)))
        settleServerOnMobileData(holder(settings), settings)
        assertEquals(false, holder(settings).config.value.sttConfig.serverOnMobileData)
    }
}
