package coredevices.util.transcription

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

internal fun sha256Fingerprint(certificate: X509Certificate): String =
    formatFingerprint(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

/** Raised from the handshake when [decideServerTrust] refuses; the message carries the fingerprint for the log. */
class UntrustedServerCertificateException(val fingerprint: String, val changed: Boolean) : CertificateException(
    if (changed) "server certificate changed, SHA-256 $fingerprint" else "server certificate not trusted, SHA-256 $fingerprint",
)

/**
 * [decideServerTrust] as an X509TrustManager: the platform verdict comes
 * from [platform], the pin from [pinnedFingerprint] at handshake time.
 * Client certificates are never used by this app, so that direction is
 * refused outright.
 */
internal class PinningTrustManager(
    private val platform: X509TrustManager,
    private val pinnedFingerprint: () -> String?,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val platformTrusted = runCatching { platform.checkServerTrusted(chain, authType) }.isSuccess
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
 * The platform's host-name check, or a pinned certificate: a self-signed
 * certificate is often issued to no name at all, and once the user has
 * confirmed its fingerprint the pin is the server's identity.
 */
internal fun pinAwareHostnameVerifier(pinnedFingerprint: () -> String?): HostnameVerifier {
    val platform = HttpsURLConnection.getDefaultHostnameVerifier()
    return HostnameVerifier { hostname, session ->
        platform.verify(hostname, session) ||
            presentedFingerprint(session)?.equals(pinnedFingerprint(), ignoreCase = true) == true
    }
}

actual fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?): HttpClient {
    val trust = PinningTrustManager(platformTrustManager(), pinnedFingerprint)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(context.socketFactory, trust)
                hostnameVerifier(pinAwareHostnameVerifier(pinnedFingerprint))
                // The dictation deadline is the caller's; these only stop a
                // dead server from holding the connection open for minutes.
                connectTimeout(3, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
            }
        }
    }
}

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

actual suspend fun probeServerCertificate(host: String, port: Int): ServerCertificateProbe = withContext(Dispatchers.IO) {
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
            platformTrustManager().checkServerTrusted(chain.toTypedArray(), leaf.publicKey.algorithm)
        }.isSuccess
        val hostnameMatches = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)
        ServerCertificateProbe(
            fingerprint = sha256Fingerprint(leaf),
            platformTrusted = platformTrusted,
            hostnameMatches = hostnameMatches,
            subject = leaf.subjectX500Principal.name,
        )
    }
}
