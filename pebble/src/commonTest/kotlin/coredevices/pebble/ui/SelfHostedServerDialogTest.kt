package coredevices.pebble.ui

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.security.DecryptResult
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import coredevices.util.transcription.SelfHostedServerStore
import coredevices.util.transcription.ServerUrlProblem
import coredevices.util.transcription.serverHostPort
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the server dialog's pure decisions: the words for a refused URL
 * and a test status, and which token a request carries once the URL has
 * been edited.
 */
class SelfHostedServerDialogTest {

    /** Keeps the order of the keys written or removed. */
    private class RecordingSettings(private val inner: MapSettings = MapSettings()) : Settings by inner {
        val writes = mutableListOf<String>()

        override fun putString(key: String, value: String) {
            writes += key
            inner.putString(key, value)
        }

        override fun remove(key: String) {
            writes += key
            inner.remove(key)
        }
    }

    /** Can fail to decrypt the way a Keystore hiccup does. */
    private class PlainCipher : SecretCipher {
        var failing = false
        override fun encrypt(plaintext: String): String = "enc:$plaintext"
        override fun decrypt(stored: String): DecryptResult =
            if (failing) DecryptResult.TransientFailure else DecryptResult.Success(stored.removePrefix("enc:"))
    }

    @Test
    fun aRequestFromTheDialogCarriesTheSavedTokenOnlyToItsServer() {
        val store = SelfHostedServerStore(MapSettings(), PlainCipher())
        val home = "stt.example.net:443"
        val other = "other.example.net:443"
        store.setToken(home, "tok-home")
        assertEquals("tok-home", dialogRequestToken(store, "", clearToken = false, savedHostPort = home, hostPort = home))
        assertNull(dialogRequestToken(store, "", clearToken = false, savedHostPort = home, hostPort = other))
        assertNull(dialogRequestToken(store, "", clearToken = false, savedHostPort = other, hostPort = other), "whatever the dialog took the saved address to be")
        assertNull(dialogRequestToken(store, "", clearToken = true, savedHostPort = home, hostPort = home))
        assertEquals("typed", dialogRequestToken(store, " typed ", clearToken = false, savedHostPort = home, hostPort = other))
        assertNull(dialogRequestToken(store, "typed", clearToken = false, savedHostPort = home, hostPort = null))
    }

    /** A Save that leaves the token alone does not read it, so it cannot take an unreadable one for none. */
    @Test
    fun saveKeepsATokenItWasNotAskedToChange() {
        val settings = MapSettings()
        val cipher = PlainCipher()
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = settings, json = Json { ignoreUnknownKeys = true })
        val store = SelfHostedServerStore(settings, cipher)
        val a = "https://a.example.net/inference"
        saveServerSettings(holder, store, a, model = "", token = "tok-a", clearToken = false, savedHostPort = null)

        cipher.failing = true
        saveServerSettings(holder, store, a, model = "base", token = "", clearToken = false, savedHostPort = serverHostPort(a))
        cipher.failing = false
        assertEquals("tok-a", store.tokenFor(serverHostPort(a)))
        assertEquals("base", holder.config.value.sttConfig.serverModel)

        store.setToken(null, null)
        EncryptedStringSetting(settings, cipher, "stt_server_token").set("older-token")
        saveServerSettings(holder, store, a, model = "", token = "", clearToken = false, savedHostPort = serverHostPort(a))
        assertTrue(settings.hasKey("stt_server_token"), "a token not yet given an address stays")
    }

    /** A request reads the address, then the token: the token is settled before the address a run starts on. */
    @Test
    fun saveWritesTheStoreBeforeTheConfig() {
        val settings = RecordingSettings()
        val holder = CoreConfigHolder(defaultValue = CoreConfig(), settings = settings, json = Json { ignoreUnknownKeys = true })
        val store = SelfHostedServerStore(settings, PlainCipher())
        val a = "https://a.example.net/inference"
        val b = "https://b.example.net/inference"
        fun save(url: String, token: String, saved: String?): List<String> {
            settings.writes.clear()
            saveServerSettings(holder, store, url, model = "", token = token, clearToken = false, savedHostPort = saved?.let(::serverHostPort))
            return settings.writes.toList()
        }
        val config = "coreapp.config"
        val tokenKey = "stt_server_token_bound"

        var writes = save(a, "tok-a", saved = null)
        assertTrue(writes.indexOf(tokenKey) in 0 until writes.indexOf(config), "a new token: $writes")
        assertEquals(config, writes.last())

        writes = save(b, "tok-b", saved = a)
        assertTrue(writes.indexOf(tokenKey) in 0 until writes.indexOf(config), "another server with its token: $writes")
        assertEquals("tok-b", store.tokenFor(serverHostPort(b)))
        assertNull(store.tokenFor(serverHostPort(a)))

        writes = save(b, "", saved = b)
        assertFalse(tokenKey in writes, "the same server and token: $writes")
        writes = save(b, "tok-b", saved = b)
        assertFalse(tokenKey in writes, "the same token typed again: $writes")
        assertEquals("tok-b", store.tokenFor(serverHostPort(b)))

        save(a, "tok-b", saved = b)
        assertEquals("tok-b", store.tokenFor(serverHostPort(a)), "the same token typed for another server is saved as that server's")
        assertNull(store.tokenFor(serverHostPort(b)))
        save(b, "tok-b", saved = a)

        writes = save(a, "", saved = b)
        assertTrue(writes.indexOf(tokenKey) in 0 until writes.indexOf(config), "another server with no token typed: $writes")
        assertNull(store.tokenFor(serverHostPort(a)), "no token for this server")
        assertNull(store.storedToken(), "the other server's token is gone")
        assertEquals(a, holder.config.value.sttConfig.serverUrl)

        store.trust(serverHostPort(a)!!, "AB:CD")
        writes = save("", "", saved = a)
        assertEquals(config, writes.last(), "the server removed: $writes")
        assertNull(store.pinnedFingerprint(serverHostPort(a)!!))
        assertNull(holder.config.value.sttConfig.serverUrl)
    }

    @Test
    fun urlProblemsAreNamedAndAnEmptyUrlIsNotOne() {
        assertEquals("", serverUrlProblemText(ServerUrlProblem.Empty))
        assertTrue(serverUrlProblemText(ServerUrlProblem.NotHttps).startsWith("Only https:// URLs"))
        assertTrue(serverUrlProblemText(ServerUrlProblem.HasCredentials).contains("token field"))
        for (problem in ServerUrlProblem.entries.filter { it != ServerUrlProblem.Empty }) {
            assertTrue(serverUrlProblemText(problem).isNotBlank(), problem.name)
        }
    }

    @Test
    fun testStatusNamesTheTokenAndThePathCases() {
        assertEquals("Server answered. Dictation can use it.", serverTestStatusText(200))
        assertEquals("Server answered. Dictation can use it.", serverTestStatusText(204))
        assertTrue(serverTestStatusText(401).contains("token"))
        assertTrue(serverTestStatusText(403).contains("token"))
        val notFound = serverTestStatusText(404)
        assertTrue(notFound.contains("/inference") && notFound.contains("/v1/audio/transcriptions"))
        assertEquals("Server returned HTTP 502.", serverTestStatusText(502))
    }

    @Test
    fun theSavedTokenStaysWithTheServerItWasSavedFor() {
        val saved = "tok-123"
        val home = "stt.example.net:443"
        assertEquals(saved, effectiveServerToken("", clearToken = false, saved, home, home))
        // Same server, different path: still the same host and port.
        assertEquals(saved, effectiveServerToken("  ", clearToken = false, saved, home, "stt.example.net:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, "stt.exarnple.net:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, "stt.example.net:8443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, null), "no server, no token")
        assertNull(effectiveServerToken(" typed ", clearToken = false, saved, home, null), "removing the server drops a typed token too")
        assertNull(effectiveServerToken("", clearToken = true, saved, home, home))
        assertEquals("typed", effectiveServerToken(" typed ", clearToken = false, saved, home, "other.test:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved = null, savedHostPort = null, hostPort = home))
    }

    @Test
    fun theTokenLabelSaysWhetherTheSavedTokenApplies() {
        val home = "stt.example.net:443"
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = false, hasSaved = false, home, home))
        assertEquals("Bearer token (saved; type to replace)", tokenFieldLabel("", clearToken = false, hasSaved = true, home, home))
        assertEquals(
            "Bearer token (the saved one is for the previous server and is removed on Save)",
            tokenFieldLabel("", clearToken = false, hasSaved = true, home, "other.test:443"),
        )
        assertEquals("Bearer token (optional)", tokenFieldLabel("typed", clearToken = false, hasSaved = true, home, home))
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = true, hasSaved = true, home, home))
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = false, hasSaved = true, home, null), "no server keeps no token")
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = false, hasSaved = true, null, null))
    }

    @Test
    fun saveLeavesAnUnchangedTokenAloneAndAlwaysWritesAClear() {
        assertFalse(writesServerToken("tok", "tok"))
        assertTrue(writesServerToken("new", "tok"))
        assertTrue(writesServerToken("tok", null))
        assertTrue(writesServerToken(null, "tok"))
        assertTrue(writesServerToken(null, null))
    }
}
