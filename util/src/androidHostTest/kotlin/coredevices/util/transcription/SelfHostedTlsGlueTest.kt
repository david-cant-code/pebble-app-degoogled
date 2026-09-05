package coredevices.util.transcription

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client and probe as built, against a local TLS server whose
 * certificate chains to a CA the host does not trust: refusal at the
 * handshake with the fingerprint when nothing is pinned, delivery once the
 * pin matches, the "changed" refusal when it does not, and the pin
 * overriding a platform that would trust the chain. The CA and server key
 * pairs are generated per run with the JDK's keytool, so no key material
 * is tracked in the tree.
 */
class SelfHostedTlsGlueTest {
    private companion object {
        const val PASSWORD = "changeit"
        const val SERVER_NAME = "stt-server-c.test"
    }

    private lateinit var workDir: File
    private lateinit var certificate: X509Certificate
    private lateinit var issuer: X509Certificate
    private lateinit var server: HttpsServer
    private val port get() = server.address.port
    private val url get() = "https://127.0.0.1:$port/inference"

    /** The host JVM's CA trust, hostname-blind like a two-argument check, and a name check that never matches. */
    private object HostPlatform : PlatformServerTrust {
        private val manager = platformTrustManager()
        override val acceptedIssuers: Array<X509Certificate> get() = manager.acceptedIssuers
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) =
            manager.checkServerTrusted(chain, authType)
        override fun verifyHostname(host: String, session: SSLSession): Boolean = false
    }

    /** A platform that trusts every chain for every name: what a CA-issued certificate for the dialled host meets. */
    private object TrustAll : PlatformServerTrust {
        override val acceptedIssuers: Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) = Unit
        override fun verifyHostname(host: String, session: SSLSession): Boolean = true
    }

    private fun keytool(vararg args: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").let { if (it.exists()) it else File(it.path + ".exe") }
        assertTrue(keytool.exists(), "keytool not found under java.home: $keytool")
        val process = ProcessBuilder(keytool.path, *args).directory(workDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "keytool ${args.first()} failed: $output")
    }

    @BeforeTest
    fun startServer() {
        workDir = File.createTempFile("stt-tls", "").apply { delete(); mkdirs() }
        val store = arrayOf("-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD)
        val ec = arrayOf("-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "30")
        // A private CA signs the server's certificate, so the chain has a
        // leaf and an issuer like a CA-issued deployment, and JSSE's
        // end-entity checks (which it skips for a self-signed anchor) run.
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=stt-test-ca", "-ext", "bc:c", "-keystore", "ca.p12", *ec, *store)
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.crt", "-rfc", *store)
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=$SERVER_NAME", "-keystore", "server.p12", *ec, *store)
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-file", "server.csr", *store)
        keytool(
            "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-infile", "server.csr", "-outfile", "server.crt",
            "-ext", "SAN=dns:$SERVER_NAME", "-validity", "30", "-sigalg", "SHA256withECDSA", "-rfc", *store,
        )
        keytool("-importcert", "-alias", "ca", "-keystore", "server.p12", "-file", "ca.crt", "-noprompt", *store)
        keytool("-importcert", "-alias", "server", "-keystore", "server.p12", "-file", "server.crt", "-noprompt", *store)

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            File(workDir, "server.p12").inputStream().use { load(it, PASSWORD.toCharArray()) }
        }
        val chain = keyStore.getCertificateChain("server").map { it as X509Certificate }
        assertEquals(2, chain.size, "the server must present the leaf and its issuer")
        certificate = chain[0]
        issuer = chain[1]
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD.toCharArray()) }.keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/inference") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val reply = """{"text":"from the local server"}""".encodeToByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            start()
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
        workDir.deleteRecursively()
    }

    private fun client(pin: String?, platform: PlatformServerTrust = HostPlatform): HttpClient =
        selfHostedHttpClient("127.0.0.1:$port", { pin }, platform)

    private suspend fun HttpClient.post(): Pair<Int, String> = use { client ->
        val parts = formData {
            append("file", ByteArray(32), Headers.build { append(HttpHeaders.ContentType, "audio/wav") })
        }
        val response = client.submitFormWithBinaryData(url, parts)
        response.status.value to response.bodyAsText()
    }

    private fun Throwable.refusal(): UntrustedServerCertificateException =
        assertNotNull(
            generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
                .firstNotNullOfOrNull { it as? UntrustedServerCertificateException },
            "no certificate refusal in the cause chain of ${this::class.simpleName}: $message",
        )

    @Test
    fun anUnpinnedSelfSignedServerIsRefusedAtTheHandshake() = runBlocking {
        val refusal = assertFailsWith<IOException> { client(pin = null).post() }.refusal()
        assertFalse(refusal.changed)
        assertEquals(sha256Fingerprint(certificate), refusal.fingerprint)
    }

    @Test
    fun thePinnedServerIsReachedAndAnswers() = runBlocking {
        val (status, body) = client(pin = sha256Fingerprint(certificate)).post()
        assertEquals(200, status)
        assertEquals("from the local server", parseServerTranscript(body))
    }

    @Test
    fun aDifferentPinRefusesTheServerAsChanged() = runBlocking {
        val refusal = assertFailsWith<IOException> { client(pin = "00:11:22").post() }.refusal()
        assertTrue(refusal.changed)
        assertEquals(sha256Fingerprint(certificate), refusal.fingerprint)
    }

    @Test
    fun aPinOverridesAPlatformThatTrustsTheChain() = runBlocking {
        assertEquals(200, client(pin = null, platform = TrustAll).post().first, "platform trust alone reaches the server")
        val refusal = assertFailsWith<IOException> { client(pin = "00:11:22", platform = TrustAll).post() }.refusal()
        assertTrue(refusal.changed, "a pin refuses a replacement the platform would accept")
    }

    @Test
    fun theProbeReportsWhatTheServerPresented() = runBlocking {
        val probe = probeServerCertificate("127.0.0.1", port, HostPlatform)
        assertEquals(sha256Fingerprint(certificate), probe.fingerprint)
        assertFalse(probe.platformTrusted)
        assertFalse(probe.hostnameMatches)
        assertTrue(probe.subject.contains("CN=$SERVER_NAME"), probe.subject)
    }

    @Test
    fun theProbePassesAnAuthTypeTheJdkTrustManagerAccepts() = runBlocking {
        // A trust manager that holds the issuing CA as an anchor, so only the authType can fail the check.
        val anchors = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("ca", issuer) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        assertFailsWith<CertificateException>("a bare key algorithm is not a TLS authType") {
            manager.checkServerTrusted(arrayOf(certificate, issuer), certificate.publicKey.algorithm)
        }
        manager.checkServerTrusted(arrayOf(certificate, issuer), tlsAuthType(certificate.publicKey.algorithm))
        val anchored = object : PlatformServerTrust {
            override val acceptedIssuers: Array<X509Certificate> get() = manager.acceptedIssuers
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) =
                manager.checkServerTrusted(chain, authType)
            override fun verifyHostname(host: String, session: SSLSession): Boolean = false
        }
        assertTrue(probeServerCertificate("127.0.0.1", port, anchored).platformTrusted)
    }
}
