package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("timed out", describeTransportFailure(Exception("Connect timeout has expired [url=$url]", ConnectTimeoutStandIn())))
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

    private class ConnectTimeoutStandIn : Exception("Connect timeout has expired")

    @Test
    fun fingerprintMatchesTheOpensslLayout() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x10, 0xAB.toByte(), 0xFF.toByte())
        assertEquals("00:0F:10:AB:FF", formatFingerprint(bytes))
    }

    @Test
    fun storeEncryptsTheTokenAndKeepsPinsPerHost() {
        val settings = MapSettings()
        val store = SelfHostedServerStore(settings, PlainCipher)
        assertNull(store.token())
        store.setToken("  secret-token  ")
        assertEquals("secret-token", store.token())
        // The token never sits in the clear.
        assertEquals(false, settings.getStringOrNull("stt_server_token")!!.contains("secret-token"))
        store.setToken("   ")
        assertNull(store.token())

        assertNull(store.pinnedFingerprint("a.test:443"))
        store.trust("a.test:443", "AA:BB")
        assertEquals("AA:BB", store.pinnedFingerprint("a.test:443"))
        assertNull(store.pinnedFingerprint("a.test:8443"))
        store.forget("a.test:443")
        assertNull(store.pinnedFingerprint("a.test:443"))
    }
}
