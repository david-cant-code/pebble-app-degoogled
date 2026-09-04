package coredevices.util.transcription

import com.russhwolf.settings.Settings
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol
import io.ktor.http.Url
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

/** Outcome of checking one server certificate against platform trust and the pin. */
enum class ServerTrust { Trusted, UnknownCertificate, ChangedCertificate }

/**
 * Trust on first use for the server's certificate. A chain the platform
 * trusts (a system or user-installed CA, with a matching host name)
 * passes as it would anywhere else. Otherwise the presented leaf
 * certificate must equal the fingerprint the user confirmed earlier;
 * with no pin the certificate is unknown, and with a different pin it has
 * changed, which is the case the user must look at before accepting.
 */
fun decideServerTrust(platformTrusted: Boolean, pinned: String?, presented: String): ServerTrust = when {
    platformTrusted -> ServerTrust.Trusted
    pinned == null -> ServerTrust.UnknownCertificate
    pinned.equals(presented, ignoreCase = true) -> ServerTrust.Trusted
    else -> ServerTrust.ChangedCertificate
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
 * [decideServerTrust]: platform trust first, the pinned fingerprint
 * second, refusal otherwise, and host-name verification that also accepts
 * a pinned certificate for a name it was not issued to. [pinnedFingerprint]
 * is read on every handshake so a fresh pin takes effect without a new
 * client.
 */
expect fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?): HttpClient
