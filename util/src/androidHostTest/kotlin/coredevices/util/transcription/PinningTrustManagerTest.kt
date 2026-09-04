package coredevices.util.transcription

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import javax.security.cert.Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TLS glue behind trust on first use, on two self-signed test
 * certificates (fixtures under resources/selfhosted, with the fingerprints
 * openssl printed for them): refusal with the fingerprint when unpinned,
 * acceptance once pinned, the "changed" refusal when the pin differs, and
 * the pin standing in for the host name.
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

    /** A platform that trusts nothing, which is what a self-signed certificate meets. */
    private val distrusting = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("unknown issuer")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trusting = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun fingerprintMatchesWhatOpensslPrints() {
        assertEquals(opensslFingerprint("server-a"), sha256Fingerprint(certificate("server-a")))
        assertEquals(opensslFingerprint("server-b"), sha256Fingerprint(certificate("server-b")))
    }

    @Test
    fun unpinnedSelfSignedIsRefusedWithItsFingerprint() {
        val manager = PinningTrustManager(distrusting) { null }
        val refusal = assertFailsWith<UntrustedServerCertificateException> {
            manager.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
        }
        assertEquals(opensslFingerprint("server-a"), refusal.fingerprint)
        assertFalse(refusal.changed)
    }

    @Test
    fun pinnedSelfSignedIsAcceptedAndADifferentOneIsAChange() {
        var pin: String? = opensslFingerprint("server-a")
        val manager = PinningTrustManager(distrusting) { pin }
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
        PinningTrustManager(trusting) { null }.checkServerTrusted(arrayOf(certificate("server-a")), "EC")
    }

    @Test
    fun clientCertificatesAreRefused() {
        assertFailsWith<CertificateException> {
            PinningTrustManager(trusting) { null }.checkClientTrusted(arrayOf(certificate("server-a")), "EC")
        }
    }

    @Test
    fun hostnameVerifierAcceptsAPinnedCertificateForAnyName() {
        val session = sessionPresenting(certificate("server-a"))
        // The fixture is issued to stt-server-a.test, never to this host; only the pin can accept it.
        assertTrue(pinAwareHostnameVerifier { opensslFingerprint("server-a") }.verify("10.0.0.5", session))
        assertFalse(pinAwareHostnameVerifier { opensslFingerprint("server-b") }.verify("10.0.0.5", session))
        assertFalse(pinAwareHostnameVerifier { null }.verify("10.0.0.5", session))
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
