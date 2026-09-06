package coredevices.util.transcription

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession
import javax.security.cert.Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The trust manager and host-name verifier behind trust on first use, on
 * two self-signed test certificates (fixtures under resources/selfhosted,
 * with the fingerprints openssl printed for them): refusal with the
 * fingerprint when unpinned, acceptance once pinned, the "changed" refusal
 * when the pin differs (a platform-trusted replacement included), and the
 * pin standing in for the host name. The client and probe built on them
 * are driven against a local TLS server in SelfHostedTlsGlueTest.
 */
class PinningTrustManagerTest {

    private fun certificate(name: String): X509Certificate =
        checkNotNull(javaClass.classLoader.getResourceAsStream("selfhosted/$name.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun opensslFingerprint(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("selfhosted/$name.sha256")).use {
            it.readBytes().decodeToString().trim()
        }

    /** A platform that trusts nothing and matches no name, which is what a self-signed certificate meets. */
    private val distrusting = object : PlatformServerTrust {
        override val acceptedIssuers: Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) =
            throw CertificateException("unknown issuer")
        override fun verifyHostname(host: String, session: SSLSession): Boolean = false
    }

    /** A platform that trusts every chain for every name: a CA-issued certificate for the dialled host. */
    private val trusting = object : PlatformServerTrust {
        override val acceptedIssuers: Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) = Unit
        override fun verifyHostname(host: String, session: SSLSession): Boolean = true
    }

    private val host = "10.0.0.5"

    @Test
    fun fingerprintMatchesWhatOpensslPrints() {
        assertEquals(opensslFingerprint("server-a"), sha256Fingerprint(certificate("server-a")))
        assertEquals(opensslFingerprint("server-b"), sha256Fingerprint(certificate("server-b")))
    }

    @Test
    fun unpinnedSelfSignedIsRefusedWithItsFingerprint() {
        val manager = PinningTrustManager(distrusting, host) { null }
        val refusal = assertFailsWith<UntrustedServerCertificateException> {
            manager.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
        }
        assertEquals(opensslFingerprint("server-a"), refusal.fingerprint)
        assertFalse(refusal.changed)
    }

    @Test
    fun pinnedSelfSignedIsAcceptedAndADifferentOneIsAChange() {
        var pin: String? = opensslFingerprint("server-a")
        val manager = PinningTrustManager(distrusting, host) { pin }
        manager.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
        val changed = assertFailsWith<UntrustedServerCertificateException> {
            manager.checkServerTrusted(arrayOf(certificate("server-b")), "EC")
        }
        assertTrue(changed.changed)
        assertEquals(opensslFingerprint("server-b"), changed.fingerprint)
        // The pin is read per handshake: re-trusting takes effect at once.
        pin = opensslFingerprint("server-b")
        manager.checkServerTrusted(arrayOf(certificate("server-b")), "EC")
    }

    @Test
    fun platformTrustedChainsPassWithoutAPin() {
        PinningTrustManager(trusting, host) { null }.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
    }

    @Test
    fun aPinRefusesAPlatformTrustedReplacement() {
        val manager = PinningTrustManager(trusting, host) { opensslFingerprint("server-a") }
        manager.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
        val changed = assertFailsWith<UntrustedServerCertificateException> {
            manager.checkServerTrusted(arrayOf(certificate("server-b")), "EC")
        }
        assertTrue(changed.changed)
        assertEquals(opensslFingerprint("server-b"), changed.fingerprint)
    }

    @Test
    fun theHostReachesThePlatformCheck() {
        var seen: String? = null
        val recording = object : PlatformServerTrust {
            override val acceptedIssuers: Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) { seen = host }
            override fun verifyHostname(host: String, session: SSLSession): Boolean = true
        }
        PinningTrustManager(recording, "stt.example.net") { null }.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
        assertEquals("stt.example.net", seen)
    }

    @Test
    fun clientCertificatesAreRefused() {
        assertFailsWith<CertificateException> {
            PinningTrustManager(trusting, host) { null }.checkClientTrusted(arrayOf(certificate("server-a")), "EC")
        }
    }

    @Test
    fun hostnameVerifierAcceptsAPinnedCertificateForAnyName() {
        val session = sessionPresenting(certificate("server-a"))
        // The fixture is issued to stt-server-a.test, never to this host; only the pin can accept it.
        assertTrue(pinAwareHostnameVerifier(distrusting) { opensslFingerprint("server-a") }.verify(host, session))
        assertFalse(pinAwareHostnameVerifier(distrusting) { opensslFingerprint("server-b") }.verify(host, session))
        assertFalse(pinAwareHostnameVerifier(distrusting) { null }.verify(host, session))
    }

    @Test
    fun hostnameVerifierFollowsThePlatformOnlyWithoutAPin() {
        val session = sessionPresenting(certificate("server-a"))
        assertTrue(pinAwareHostnameVerifier(trusting) { null }.verify(host, session))
        // With a pin, a name the platform would accept is not enough: the certificate must be the pinned one.
        assertFalse(pinAwareHostnameVerifier(trusting) { opensslFingerprint("server-b") }.verify(host, session))
        assertTrue(pinAwareHostnameVerifier(trusting) { opensslFingerprint("server-a") }.verify(host, session))
    }

    @Test
    fun probeAuthTypeIsALegalTlsName() {
        assertEquals("ECDHE_ECDSA", tlsAuthType("EC"))
        assertEquals("ECDHE_RSA", tlsAuthType("RSA"))
        assertEquals("UNKNOWN", tlsAuthType("EdDSA"))
    }

    /** Only the peer certificate is read from the session. */
    private fun sessionPresenting(leaf: X509Certificate): SSLSession =
        java.lang.reflect.Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf<java.security.cert.Certificate>(leaf)
                "getPeerCertificateChain" -> arrayOf<Certificate>()
                "getPeerHost" -> "10.0.0.5"
                "getPeerPort" -> 443
                "getProtocol" -> "TLSv1.3"
                "getCipherSuite" -> "TLS_AES_128_GCM_SHA256"
                "toString" -> "fake session"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SSLSession
}
