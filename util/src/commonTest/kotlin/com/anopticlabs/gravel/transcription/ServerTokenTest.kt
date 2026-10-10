package com.anopticlabs.gravel.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.security.DecryptResult
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import coredevices.util.transcription.SelfHostedServerStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServerTokenTest {

    private object PlainCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): DecryptResult = DecryptResult.Success(stored.removePrefix("enc:"))
    }

    /** An install with [url] saved and a token from before tokens carried an address. */
    private fun adopted(url: String?): Pair<MapSettings, SelfHostedServerStore> {
        val settings = MapSettings()
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = settings, json = Json { ignoreUnknownKeys = true })
        holder.update(holder.config.value.let { it.copy(sttConfig = it.sttConfig.copy(serverUrl = url)) })
        EncryptedStringSetting(settings, PlainCipher, "stt_server_token").set("old-token")
        val store = SelfHostedServerStore(settings, PlainCipher)
        adoptUnboundServerToken(holder, store)
        return settings to store
    }

    @Test
    fun anOlderTokenBecomesTheConfiguredServers() {
        val (settings, store) = adopted(" https://stt.example.net:8443/inference ")
        assertEquals("old-token", store.tokenFor("stt.example.net:8443"))
        assertNull(store.tokenFor("stt.example.net:443"))
        assertFalse(settings.hasKey("stt_server_token"))
    }

    @Test
    fun withoutAUsableServerAnOlderTokenIsRemoved() {
        for (url in listOf(null, "", "http://stt.example.net/inference")) {
            val (settings, store) = adopted(url)
            assertFalse(settings.hasKey("stt_server_token"), "$url")
            assertNull(store.storedToken(), "$url")
        }
    }
}
