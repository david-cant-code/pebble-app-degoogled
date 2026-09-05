package coredevices.util.transcription

import android.net.http.X509TrustManagerExtensions
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The platform's own server trust: the system CA store plus the CAs the
 * user installed, exactly as the network security config allows for the
 * rest of the app.
 */
internal fun platformTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

/**
 * The platform's view of a server, behind one seam so the TLS glue runs
 * against a local server under host tests: whether it trusts a chain for
 * a host, and whether a certificate is for the host that was dialled.
 */
internal interface PlatformServerTrust {
    val acceptedIssuers: Array<X509Certificate>

    /** Throws [CertificateException] when the platform does not trust [chain] for [host]. */
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String)

    fun verifyHostname(host: String, session: SSLSession): Boolean
}

/**
 * Android's view: [platformTrustManager] through the hostname-aware
 * check, and the default host-name verifier. The hostname-aware overload
 * is the one the platform requires once the network security config
 * carries any per-domain entry; its two-argument form is refused then
 * (AOSP frameworks/base
 * `android.security.net.config.RootTrustManager.checkServerTrusted`).
 */
internal class AndroidServerTrust(private val manager: X509TrustManager = platformTrustManager()) : PlatformServerTrust {
    private val extensions = X509TrustManagerExtensions(manager)
    private val hostnames: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    override val acceptedIssuers: Array<X509Certificate> get() = manager.acceptedIssuers

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String) {
        extensions.checkServerTrusted(chain, authType, host)
    }

    override fun verifyHostname(host: String, session: SSLSession): Boolean = hostnames.verify(host, session)
}

internal fun sha256Fingerprint(certificate: X509Certificate): String =
    formatFingerprint(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

/** Raised from the handshake when [decideServerTrust] refuses. */
class UntrustedServerCertificateException(
    override val fingerprint: String,
    override val changed: Boolean,
) : CertificateException(), ServerCertificateRefusal {
    override val message: String get() = describe()
}

/**
 * [decideServerTrust] as an X509TrustManager for one host: the platform
 * verdict comes from [platform], the pin from [pinnedFingerprint] at
 * handshake time. Client certificates are never used by this app, so
 * that direction is refused outright.
 */
internal class PinningTrustManager(
    private val platform: PlatformServerTrust,
    private val host: String,
    private val pinnedFingerprint: () -> String?,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val platformTrusted = runCatching { platform.checkServerTrusted(chain, authType, host) }.isSuccess
        val presented = sha256Fingerprint(chain[0])
        when (decideServerTrust(platformTrusted, pinnedFingerprint(), presented)) {
            ServerTrust.Trusted -> return
            ServerTrust.UnknownCertificate -> throw UntrustedServerCertificateException(presented, changed = false)
            ServerTrust.ChangedCertificate -> throw UntrustedServerCertificateException(presented, changed = true)
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

private fun presentedFingerprint(session: SSLSession): String? =
    (runCatching { session.peerCertificates }.getOrNull()?.firstOrNull() as? X509Certificate)?.let(::sha256Fingerprint)

/**
 * Host-name verification under the same rule as the trust manager: with
 * a pin, only the pinned certificate passes, whatever name it was issued
 * to, since a self-signed certificate is often issued to no name and the
 * confirmed fingerprint is the server's identity; without one, the
 * platform's own check.
 */
internal fun pinAwareHostnameVerifier(platform: PlatformServerTrust, pinnedFingerprint: () -> String?): HostnameVerifier =
    HostnameVerifier { hostname, session ->
        val pin = pinnedFingerprint()
        if (pin != null) {
            presentedFingerprint(session)?.equals(pin, ignoreCase = true) == true
        } else {
            platform.verifyHostname(hostname, session)
        }
    }

/**
 * A TLS authentication type for the platform check at the probe, which
 * has no handshake to take one from. JSSE accepts only the key-exchange
 * names and rejects a bare key algorithm (JDK 17
 * `sun.security.validator.EndEntityChecker.checkTLSServer`); Android's
 * Conscrypt requires the string to be non-empty and reads nothing else
 * from it (`org.conscrypt.TrustManagerImpl.checkTrusted`).
 */
internal fun tlsAuthType(keyAlgorithm: String): String = when (keyAlgorithm.uppercase()) {
    "EC" -> "ECDHE_ECDSA"
    "RSA" -> "ECDHE_RSA"
    else -> "UNKNOWN"
}

/** [selfHostedHttpClient] with the platform's view injected, for host tests against a local server. */
internal fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?, platform: PlatformServerTrust): HttpClient {
    val trust = PinningTrustManager(platform, hostPort.substringBeforeLast(':'), pinnedFingerprint)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(context.socketFactory, trust)
                hostnameVerifier(pinAwareHostnameVerifier(platform, pinnedFingerprint))
                // The dictation deadline is the caller's; these only stop a
                // dead server from holding the connection open for minutes.
                connectTimeout(3, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
            }
        }
    }
}

actual fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?): HttpClient =
    selfHostedHttpClient(hostPort, pinnedFingerprint, AndroidServerTrust())

/** Accepts every chain and keeps it: for the probe handshake only, which sends nothing. */
private class RecordingTrustManager : X509TrustManager {
    var chain: List<X509Certificate> = emptyList()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        this.chain = chain.toList()
    }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not used")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** [probeServerCertificate] with the platform's view injected, for host tests against a local server. */
internal suspend fun probeServerCertificate(host: String, port: Int, platform: PlatformServerTrust): ServerCertificateProbe =
    withContext(Dispatchers.IO) {
        val recorder = RecordingTrustManager()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(recorder), null) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use { tls ->
            tls.connect(InetSocketAddress(host, port), 3_000)
            tls.soTimeout = 5_000
            // SNI, so a proxy fronting several names presents the right one;
            // an IP literal has no server name and is dialled without.
            runCatching { SNIHostName(host) }.getOrNull()?.let { name ->
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(name) }
            }
            tls.startHandshake()
            val chain = recorder.chain.ifEmpty {
                tls.session.peerCertificates.filterIsInstance<X509Certificate>()
            }
            val leaf = chain.first()
            val platformTrusted = runCatching {
                platform.checkServerTrusted(chain.toTypedArray(), tlsAuthType(leaf.publicKey.algorithm), host)
            }.isSuccess
            ServerCertificateProbe(
                fingerprint = sha256Fingerprint(leaf),
                platformTrusted = platformTrusted,
                hostnameMatches = platform.verifyHostname(host, tls.session),
                subject = leaf.subjectX500Principal.name,
            )
        }
    }

actual suspend fun probeServerCertificate(host: String, port: Int): ServerCertificateProbe =
    probeServerCertificate(host, port, AndroidServerTrust())
