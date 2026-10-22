package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.security.DecryptResult
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the self-hosted server rules that carry security weight: the URL
 * policy, the trust-on-first-use decision, the fingerprint format the
 * user compares by eye, the store's token and pin handling, and the
 * address-free wording of a transport failure.
 */
class SelfHostedServerTest {

    /** Reversible stand-in for the keystore cipher; the storage contract is what is under test. */
    private object PlainCipher : SecretCipher {
        // Reversed rather than copied, so "the token is not stored in the clear" is a real check.
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(stored: String): DecryptResult =
            if (stored.startsWith("enc:")) DecryptResult.Success(stored.removePrefix("enc:").reversed()) else DecryptResult.Unrecoverable
    }

    @Test
    fun urlMustBeHttpsWithAHostAndWithoutCredentials() {
        assertNull(validateServerUrl("https://stt.example.net/inference"))
        assertNull(validateServerUrl(" https://10.0.0.5:8443/v1/audio/transcriptions "))
        assertEquals(ServerUrlProblem.Empty, validateServerUrl("   "))
        assertEquals(ServerUrlProblem.NotHttps, validateServerUrl("http://10.0.0.5:8080/inference"))
        assertEquals(ServerUrlProblem.NotHttps, validateServerUrl("stt.example.net/inference"))
        assertEquals(ServerUrlProblem.HasCredentials, validateServerUrl("https://user:secret@stt.example.net/inference"))
        assertEquals(ServerUrlProblem.Malformed, validateServerUrl("https://exa mple.net/"))
    }

    @Test
    fun hostPortKeysTrustByHostAndPortOnly() {
        assertEquals("stt.example.net:443", serverHostPort("https://stt.example.net/inference"))
        assertEquals("stt.example.net:443", serverHostPort("https://STT.example.net/v1/audio/transcriptions"))
        assertEquals("10.0.0.5:8443", serverHostPort("https://10.0.0.5:8443/inference"))
        assertNull(serverHostPort("http://10.0.0.5:8080/inference"))
        // Two names a client dials as different servers: capital letters with a last sigma, and small ones with a final sigma.
        val capitals = "https://\u039F\u0394\u039F\u03A3/inference"
        val small = "https://\u03BF\u03B4\u03BF\u03C2/inference"
        assertNotEquals(serverHostPort(capitals), serverHostPort(small))
    }

    @Test
    fun replyParsingReadsTextAndNothingElse() {
        assertEquals(" hello world", parseServerTranscript("""{"text":" hello world"}"""))
        assertEquals("hi", parseServerTranscript("""{"task":"transcribe","language":"en","text":"hi","segments":[]}"""))
        assertNull(parseServerTranscript("""{"error":"no file"}"""))
        assertNull(parseServerTranscript("<html>not json</html>"))
    }

    @Test
    fun transcriptsAreOneLineOfSingleSpacedWords() {
        assertEquals(
            "Text Eric, the kids have breaded shrimp. Period. We need raw shrimp.",
            normalizeServerTranscript(" Text Eric, the kids have breaded shrimp.\n Period.  We need raw shrimp.\n"),
        )
        assertEquals("", normalizeServerTranscript(" \n\t "))
    }

    @Test
    fun aPinDecidesAloneAndPlatformTrustAppliesOnlyWithoutOne() {
        val a = "AA:BB"
        val b = "CC:DD"
        assertEquals(ServerTrust.Trusted, decideServerTrust(platformTrusted = true, pinned = null, presented = a))
        assertEquals(ServerTrust.UnknownCertificate, decideServerTrust(platformTrusted = false, pinned = null, presented = a))
        assertEquals(ServerTrust.Trusted, decideServerTrust(platformTrusted = false, pinned = a, presented = a))
        assertEquals(ServerTrust.Trusted, decideServerTrust(platformTrusted = false, pinned = a.lowercase(), presented = a))
        assertEquals(ServerTrust.ChangedCertificate, decideServerTrust(platformTrusted = false, pinned = b, presented = a))
        // A CA-issued certificate for the pinned host is the interception case, so the pin still refuses it.
        assertEquals(ServerTrust.ChangedCertificate, decideServerTrust(platformTrusted = true, pinned = b, presented = a))
        assertEquals(ServerTrust.Trusted, decideServerTrust(platformTrusted = true, pinned = a, presented = a))
    }

    private class Refusal(override val fingerprint: String, override val changed: Boolean) : Exception(), ServerCertificateRefusal

    @Test
    fun transportFailuresAreDescribedWithoutTheAddress() {
        val url = "https://stt.example.net:8443/inference"
        assertEquals("timed out", describeTransportFailure(java.net.SocketTimeoutException("Read timed out [url=$url]")))
        // The OkHttp engine raises Ktor's ConnectTimeoutException over the socket's own timeout.
        assertEquals(
            "timed out",
            describeTransportFailure(
                io.ktor.client.network.sockets.ConnectTimeoutException(
                    "Connect timeout has expired [url=$url, connect_timeout=unknown ms]",
                    java.net.SocketTimeoutException("failed to connect to stt.example.net/10.0.0.5:8443"),
                ),
            ),
        )
        assertEquals("host name not found", describeTransportFailure(java.net.UnknownHostException("Unable to resolve host \"stt.example.net\"")))
        assertEquals("connection refused", describeTransportFailure(java.net.ConnectException("Failed to connect to stt.example.net/10.0.0.5:8443")))
        assertEquals(
            "certificate is not for this host",
            describeTransportFailure(javax.net.ssl.SSLPeerUnverifiedException("Hostname stt.example.net not verified")),
        )
        assertEquals("TLS handshake failed", describeTransportFailure(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
        // A refused certificate keeps its own words, wherever it sits in the cause chain.
        val refused = javax.net.ssl.SSLHandshakeException("handshake").apply { initCause(Refusal("AA:BB", changed = true)) }
        assertEquals("server certificate changed, SHA-256 AA:BB", describeTransportFailure(refused))
        assertEquals("IOException", describeTransportFailure(java.io.IOException("unexpected end of stream on $url")))
        // A cause cycle ends the walk at its bound instead of running away.
        val a = java.io.IOException("a")
        val b = java.io.IOException("b", a)
        a.initCause(b)
        assertEquals("IOException", describeTransportFailure(a))
    }

    @Test
    fun fingerprintMatchesTheOpensslLayout() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x10, 0xAB.toByte(), 0xFF.toByte())
        assertEquals("00:0F:10:AB:FF", formatFingerprint(bytes))
    }

    @Test
    fun theTokenIsReadOnlyForTheServerItWasSavedFor() {
        val store = SelfHostedServerStore(MapSettings(), PlainCipher)
        store.setToken("a.test:443", "tok-a")
        assertNull(store.tokenFor("b.test:443"))
        assertNull(store.tokenFor("a.test:8443"), "another port is another server")
        assertNull(store.tokenFor(null))
        store.setToken("b.test:443", "tok-b")
        assertNull(store.tokenFor("a.test:443"), "one token is kept")
        assertEquals("tok-b", store.tokenFor("b.test:443"))
        store.setToken(null, "tok-c")
        assertNull(store.tokenFor("b.test:443"), "no server, no token")
        assertNull(store.storedToken())
        store.setToken("b.test:443", "line one\nline two")
        assertEquals("line one\nline two", store.tokenFor("b.test:443"))
    }

    /** A cipher that can fail the way a Keystore hiccup does. */
    private class FlakyCipher : SecretCipher {
        var failing = false
        var failingEncrypt = false
        override fun encrypt(plaintext: String): String? = if (failingEncrypt) null else "enc:" + plaintext.reversed()
        override fun decrypt(stored: String): DecryptResult =
            if (failing) DecryptResult.TransientFailure else DecryptResult.Success(stored.removePrefix("enc:").reversed())
    }

    private fun unbound(settings: MapSettings, cipher: SecretCipher, token: String) =
        EncryptedStringSetting(settings, cipher, "stt_server_token").set(token)

    @Test
    fun aTokenSavedBeforeTokensCarriedAnAddressIsNoServersUntilAdopted() {
        val settings = MapSettings()
        val cipher = FlakyCipher()
        val store = SelfHostedServerStore(settings, cipher)
        unbound(settings, cipher, "old-token")
        assertNull(store.tokenFor("a.test:443"))
        assertNull(store.storedToken())

        cipher.failing = true
        store.adoptUnboundToken("a.test:443")
        assertNull(store.tokenFor("a.test:443"))
        assertTrue(settings.hasKey("stt_server_token"), "kept while it cannot be read")
        cipher.failing = false
        cipher.failingEncrypt = true
        store.adoptUnboundToken("a.test:443")
        assertTrue(settings.hasKey("stt_server_token"), "kept while it cannot be stored")
        cipher.failingEncrypt = false

        store.adoptUnboundToken("a.test:443")
        assertEquals("old-token", store.tokenFor("a.test:443"))
        assertNull(store.tokenFor("b.test:443"))
        assertEquals(false, settings.hasKey("stt_server_token"))
    }

    @Test
    fun anUnboundTokenIsRemovedWithoutAServerAndTakesTheSavedOnesPlace() {
        val settings = MapSettings()
        val store = SelfHostedServerStore(settings, PlainCipher)
        unbound(settings, PlainCipher, "old-token")
        store.adoptUnboundToken(null)
        assertEquals(false, settings.hasKey("stt_server_token"))
        assertNull(store.tokenFor("a.test:443"))

        store.setToken("a.test:443", "new-token")
        unbound(settings, PlainCipher, "old-token")
        store.adoptUnboundToken("b.test:443")
        assertEquals("old-token", store.tokenFor("b.test:443"), "an older build wrote it after the saved one")
        assertNull(store.tokenFor("a.test:443"))
        assertEquals(false, settings.hasKey("stt_server_token"))

        unbound(settings, PlainCipher, "old-token")
        store.setToken("a.test:443", "newer-token")
        assertEquals(false, settings.hasKey("stt_server_token"), "a save leaves no unbound token behind")
    }

    @Test
    fun storeEncryptsTheTokenAndKeepsPinsPerHost() {
        val settings = MapSettings()
        val store = SelfHostedServerStore(settings, PlainCipher)
        assertNull(store.tokenFor("a.test:443"))
        store.setToken("a.test:443", "  secret-token  ")
        assertEquals("secret-token", store.tokenFor("a.test:443"))
        // Neither the token nor its address sits in the clear.
        val stored = settings.getStringOrNull("stt_server_token_bound")!!
        assertEquals(false, stored.contains("secret-token") || stored.contains("a.test"))
        store.setToken("a.test:443", "   ")
        assertNull(store.tokenFor("a.test:443"))

        assertNull(store.pinnedFingerprint("a.test:443"))
        store.trust("a.test:443", "AA:BB")
        assertEquals("AA:BB", store.pinnedFingerprint("a.test:443"))
        assertNull(store.pinnedFingerprint("a.test:8443"))
        store.forget("a.test:443")
        assertNull(store.pinnedFingerprint("a.test:443"))
    }
}
