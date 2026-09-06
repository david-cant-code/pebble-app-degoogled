package coredevices.util.transcription

import com.russhwolf.settings.Settings
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why a self-hosted server URL was rejected. */
enum class ServerUrlProblem { Empty, Malformed, NotHttps, NoHost, HasCredentials }

/**
 * The rule for a self-hosted server URL: https only (cleartext is blocked
 * app-wide, and the payload is the user's voice plus a credential), a
 * host, and no credentials inside the URL, since the token has its own
 * encrypted field. The path is the user's own: whisper.cpp's server
 * listens on `/inference` and OpenAI-style servers on
 * `/v1/audio/transcriptions`, so nothing is guessed or appended.
 */
fun validateServerUrl(raw: String): ServerUrlProblem? {
    val text = raw.trim()
    if (text.isEmpty()) return ServerUrlProblem.Empty
    if (!text.lowercase().startsWith("https://")) return ServerUrlProblem.NotHttps
    val url = runCatching { Url(text) }.getOrNull() ?: return ServerUrlProblem.Malformed
    if (url.protocol != URLProtocol.HTTPS) return ServerUrlProblem.NotHttps
    if (url.host.isBlank()) return ServerUrlProblem.NoHost
    // Ktor's parser is lenient about what it accepts as a host.
    if (url.host.any { it.isWhitespace() || it.isISOControl() }) return ServerUrlProblem.Malformed
    if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return ServerUrlProblem.HasCredentials
    return null
}

/**
 * `host:port` of a valid server URL (443 when the URL names no port), the
 * key certificate trust is remembered under, or null for an invalid URL.
 * Keyed this way, editing the path keeps the trust and changing the host
 * starts over.
 */
fun serverHostPort(raw: String): String? {
    if (validateServerUrl(raw) != null) return null
    val url = Url(raw.trim())
    return "${url.host.lowercase()}:${url.port}"
}

/** The JSON both server families answer with; anything without `text` is a service error. */
@Serializable
internal data class ServerTranscriptionResponse(val text: String? = null)

private val serverJson = Json { ignoreUnknownKeys = true }

/** The transcript in a server reply, or null when the body is not JSON with a `text` field. */
fun parseServerTranscript(body: String): String? =
    runCatching { serverJson.decodeFromString<ServerTranscriptionResponse>(body).text }.getOrNull()

private val whitespaceRun = Regex("\\s+")

/**
 * One line of single-spaced words. whisper.cpp's server joins its segments
 * with newlines, and the watch refuses a dictation result whose words
 * carry them, so every run of whitespace becomes one space.
 */
fun normalizeServerTranscript(text: String): String = text.trim().replace(whitespaceRun, " ")

/** Outcome of checking one server certificate against the pin and platform trust. */
enum class ServerTrust { Trusted, UnknownCertificate, ChangedCertificate }

/**
 * Trust on first use for the server's certificate. Once the user has
 * pinned a fingerprint for the host and port, the pin decides alone: the
 * presented leaf certificate must equal it, and any other certificate
 * has changed, which the user must look at before accepting, even when
 * the platform trusts its chain, since a certificate for the same name
 * from a CA is exactly what an interceptor would present. With no pin, a
 * chain the platform trusts (a system or user-installed CA, with a
 * matching host name) passes as it would anywhere else, and anything
 * else is unknown until the user pins it. Forgetting the pin returns the
 * host to platform trust, which is the path for a deliberate move to a
 * CA-issued certificate.
 */
fun decideServerTrust(platformTrusted: Boolean, pinned: String?, presented: String): ServerTrust = when {
    pinned != null -> if (pinned.equals(presented, ignoreCase = true)) ServerTrust.Trusted else ServerTrust.ChangedCertificate
    platformTrusted -> ServerTrust.Trusted
    else -> ServerTrust.UnknownCertificate
}

/** A certificate the trust rule refused, as the handshake reports it to the caller. */
interface ServerCertificateRefusal {
    /** SHA-256 fingerprint of the presented leaf certificate, in [formatFingerprint]'s layout. */
    val fingerprint: String

    /** True when a pin existed and the certificate differs from it; false when nothing was pinned. */
    val changed: Boolean
}

/** The refusal in the words the settings dialog and the log show; the fingerprint is public to any client that connects. */
fun ServerCertificateRefusal.describe(): String =
    if (changed) "server certificate changed, SHA-256 $fingerprint" else "server certificate not trusted, SHA-256 $fingerprint"

/**
 * A transport failure with the peer's address removed. Ktor's and OkHttp's
 * own messages carry the URL, host and port, and the app log that "Export
 * logs" ships must not, so this stands in as the cause of the reported
 * network error.
 */
class ServerUnreachableException(reason: String) : IOException(reason)

/**
 * How far a cause chain is read. A cycle longer than a self-cause is legal
 * (`java.base` `java/lang/Throwable.java`, `initCause` refuses only
 * `this`), and an unbounded walk over one never ends.
 */
internal const val MAX_CAUSE_DEPTH = 16

/**
 * Why a request to the server failed at the transport, in words that name
 * neither the URL nor the address: a certificate refusal by its own
 * description, otherwise a category read off the exception classes in
 * the cause chain.
 */
fun describeTransportFailure(failure: Throwable): String {
    val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    chain.firstNotNullOfOrNull { it as? ServerCertificateRefusal }?.let { return it.describe() }
    val names = chain.map { it::class.simpleName.orEmpty() }
    return when {
        names.any { it.contains("Timeout") } -> "timed out"
        names.any { it == "UnknownHostException" } -> "host name not found"
        names.any { it == "ConnectException" } -> "connection refused"
        names.any { it == "SSLPeerUnverifiedException" } -> "certificate is not for this host"
        names.any { it.startsWith("SSL") } -> "TLS handshake failed"
        else -> names.firstOrNull { it.isNotEmpty() } ?: "network error"
    }
}

/** `AB:CD:...` upper-case hex, the form `openssl x509 -fingerprint -sha256` prints, so the user can compare. */
fun formatFingerprint(sha256: ByteArray): String =
    sha256.joinToString(":") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

/**
 * What the app remembers about the user's server besides its URL and
 * model: the bearer token, encrypted at rest through the keystore-backed
 * setting, and the certificate fingerprint pinned by trust on first use,
 * keyed by `host:port`.
 */
class SelfHostedServerStore(private val settings: Settings, cipher: SecretCipher) {
    private companion object {
        const val TOKEN_KEY = "stt_server_token"
        const val PIN_PREFIX = "stt_server_pin_"
    }

    private val tokenSetting = EncryptedStringSetting(settings, cipher, TOKEN_KEY)

    fun token(): String? = tokenSetting.get()?.takeIf { it.isNotBlank() }

    fun setToken(value: String?) = tokenSetting.set(value?.trim()?.takeIf { it.isNotBlank() })

    fun pinnedFingerprint(hostPort: String): String? = settings.getStringOrNull(PIN_PREFIX + hostPort)

    fun trust(hostPort: String, fingerprint: String) = settings.putString(PIN_PREFIX + hostPort, fingerprint)

    fun forget(hostPort: String) = settings.remove(PIN_PREFIX + hostPort)
}

/**
 * What a server presented on a TLS probe: the leaf certificate's SHA-256
 * fingerprint, whether the platform trusts the chain, whether the
 * certificate is for the host that was dialled, and who it says it is.
 */
data class ServerCertificateProbe(
    val fingerprint: String,
    val platformTrusted: Boolean,
    val hostnameMatches: Boolean,
    val subject: String,
)

/**
 * Connects to `host:port`, completes a TLS handshake that accepts any
 * certificate, records what was presented, and closes without sending a
 * byte. The confirmation dialog shows the result; nothing else uses a
 * connection that trusted blindly. Throws on connection failure.
 */
expect suspend fun probeServerCertificate(host: String, port: Int): ServerCertificateProbe

/**
 * An HTTP client for the self-hosted server whose certificate check is
 * [decideServerTrust]: the pinned fingerprint once one exists, platform
 * trust otherwise, refusal for the rest, and host-name verification that
 * a pinned certificate satisfies whatever name it was issued to.
 * [pinnedFingerprint] is read on every handshake so a fresh pin takes
 * effect without a new client.
 */
expect fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?): HttpClient
