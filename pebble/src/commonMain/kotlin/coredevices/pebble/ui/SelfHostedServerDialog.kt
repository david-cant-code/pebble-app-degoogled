package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coredevices.ui.M3Dialog
import coredevices.util.CoreConfigHolder
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.SelfHostedServerStore
import coredevices.util.transcription.SelfHostedTranscriptionService
import coredevices.util.transcription.ServerCertificateProbe
import coredevices.util.transcription.ServerTrust
import coredevices.util.transcription.ServerUrlProblem
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.decideServerTrust
import coredevices.util.transcription.probeServerCertificate
import coredevices.util.transcription.serverHostPort
import coredevices.util.transcription.validateServerUrl
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The user-facing reason a URL is refused; empty input is "no server", not an error. */
internal fun serverUrlProblemText(problem: ServerUrlProblem): String = when (problem) {
    ServerUrlProblem.Empty -> ""
    ServerUrlProblem.Malformed -> "That is not a valid URL."
    ServerUrlProblem.NotHttps -> "Only https:// URLs are accepted. The setup notes cover TLS for a home server."
    ServerUrlProblem.NoHost -> "The URL needs a host name or address."
    ServerUrlProblem.HasCredentials -> "Put the token in the token field, not in the URL."
}

/** What the connection test says for an HTTP status the server answered with. */
internal fun serverTestStatusText(status: Int): String = when (status) {
    in 200..299 -> "Server answered. Dictation can use it."
    401, 403 -> "Server rejected the token (HTTP $status)."
    404 -> "Nothing at that path (HTTP 404). whisper.cpp's server listens on /inference; OpenAI-style servers on /v1/audio/transcriptions."
    else -> "Server returned HTTP $status."
}

/**
 * The token a request from the dialog carries, and the one kept on save:
 * none without a server ([hostPort] null, the URL removed), else the typed
 * one, else the saved one only while [hostPort] is the host and port it
 * was saved with. A token is a credential for one server, so an edited URL
 * never carries it to another and a removed server does not leave it
 * behind.
 */
internal fun effectiveServerToken(typed: String, clearToken: Boolean, saved: String?, savedHostPort: String?, hostPort: String?): String? = when {
    clearToken || hostPort == null -> null
    typed.isNotBlank() -> typed.trim()
    saved != null && hostPort == savedHostPort -> saved
    else -> null
}

/** The token field's label: whether a saved token exists, and whether it applies to the host and port typed. */
internal fun tokenFieldLabel(typed: String, clearToken: Boolean, hasSaved: Boolean, savedHostPort: String?, hostPort: String?): String = when {
    // Without a server the save keeps no token, so nothing is "saved" to replace.
    typed.isNotBlank() || clearToken || !hasSaved || hostPort == null -> "Bearer token (optional)"
    hostPort == savedHostPort -> "Bearer token (saved; type to replace)"
    else -> "Bearer token (the saved one stays with the previous server)"
}

/**
 * Configures the self-hosted transcription server: URL, optional model
 * name and bearer token, and the certificate trust. "Test connection"
 * first probes the TLS certificate; one the platform does not trust is
 * shown by fingerprint for the user to compare with the server's own
 * before it is pinned, and a pinned certificate that changed is called
 * out as such. Only then is a request sent, so a wrong token or path is
 * found before saving. The saved token belongs to the host and port it
 * was saved with: a URL edited to another server is tested and saved
 * without it unless a new one is typed. Saving an empty URL removes the
 * server and drops its token and pin.
 */
@Composable
fun SelfHostedServerDialog(onDismissRequest: () -> Unit) {
    val coreConfigHolder: CoreConfigHolder = koinInject()
    val store: SelfHostedServerStore = koinInject()
    val service: SelfHostedTranscriptionService = koinInject()
    val scope = rememberCoroutineScope()
    val initial = remember { coreConfigHolder.config.value.sttConfig }

    var url by remember { mutableStateOf(initial.serverUrl ?: "") }
    var model by remember { mutableStateOf(initial.serverModel ?: "") }
    var token by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }
    val hasStoredToken = remember { store.token() != null }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ServerCertificateProbe?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Bumped when a pin is written or forgotten, so the trust line re-reads the store.
    var pinVersion by remember { mutableIntStateOf(0) }

    val urlProblem = validateServerUrl(url).takeUnless { it == ServerUrlProblem.Empty }
    val hostPort = serverHostPort(url)
    val savedHostPort = remember { initial.serverUrl?.let(::serverHostPort) }
    val pinned = remember(hostPort, pinVersion) { hostPort?.let { store.pinnedFingerprint(it) } }

    fun effectiveToken(): String? = effectiveServerToken(token, clearToken, store.token(), savedHostPort, hostPort)

    fun runRequestTest() {
        scope.launch {
            busy = true
            status = try {
                serverTestStatusText(service.testConnection(url.trim(), model.trim().ifBlank { null }, effectiveToken()))
            } catch (e: TranscriptionException.TranscriptionNetworkError) {
                "Could not reach the server: ${e.cause?.message ?: "network error"}"
            } catch (e: TranscriptionException) {
                e.message ?: "The request failed."
            } finally {
                busy = false
            }
        }
    }

    fun test() {
        val hp = hostPort ?: return
        scope.launch {
            busy = true
            status = null
            pending = null
            try {
                val probe = probeServerCertificate(hp.substringBeforeLast(':'), hp.substringAfterLast(':').toInt())
                val decision = decideServerTrust(
                    platformTrusted = probe.platformTrusted && probe.hostnameMatches,
                    pinned = store.pinnedFingerprint(hp),
                    presented = probe.fingerprint,
                )
                if (decision == ServerTrust.Trusted) runRequestTest() else pending = probe
            } catch (e: Exception) {
                status = "Could not connect: ${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun trustPending() {
        val probe = pending ?: return
        val hp = hostPort ?: return
        store.trust(hp, probe.fingerprint)
        pinVersion++
        pending = null
        runRequestTest()
    }

    fun save() {
        val cleaned = url.trim().ifBlank { null }
        val config = coreConfigHolder.config.value
        val mode = config.sttConfig.mode
        val serverModes = setOf(CactusSTTMode.RemoteOnly, CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst)
        coreConfigHolder.update(
            config.copy(
                sttConfig = config.sttConfig.copy(
                    serverUrl = cleaned,
                    serverModel = model.trim().ifBlank { null },
                    // Without a server the server modes have nothing to run; local is the only complete choice.
                    mode = if (cleaned == null && mode in serverModes) CactusSTTMode.LocalOnly else mode,
                ),
            ),
        )
        store.setToken(effectiveServerToken(token, clearToken, store.token(), savedHostPort, cleaned?.let(::serverHostPort)))
        if (cleaned == null) savedHostPort?.let(store::forget)
        onDismissRequest()
    }

    M3Dialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = { Text("Self-hosted server") },
        scrollableContent = true,
        verticalButtons = {
            TextButton(onClick = { save() }, enabled = !busy && urlProblem == null) {
                Text(if (url.isBlank() && initial.serverUrl != null) "Remove server" else "Save")
            }
            TextButton(onClick = onDismissRequest, enabled = !busy) { Text("Cancel") }
        },
    ) {
        Column {
            Text(
                "Sends dictation audio to your own transcription server over https. Works with " +
                    "whisper.cpp's server and any OpenAI-compatible server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; status = null; pending = null },
                label = { Text("Server URL") },
                placeholder = { Text("https://stt.home.lan:8443/inference") },
                singleLine = true,
                isError = urlProblem != null,
                supportingText = urlProblem?.let { { Text(serverUrlProblemText(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model name (optional)") },
                supportingText = { Text("Leave empty for whisper.cpp's server.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; clearToken = false },
                label = { Text(tokenFieldLabel(token, clearToken, hasStoredToken, savedHostPort, hostPort)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasStoredToken && !clearToken && hostPort == savedHostPort) {
                TextButton(onClick = { clearToken = true; token = "" }) { Text("Clear saved token") }
            }
            Spacer(Modifier.height(8.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { test() }, enabled = urlProblem == null && hostPort != null) { Text("Test connection") }
            }
            pending?.let { probe ->
                val changed = pinned != null
                Text(
                    if (changed) {
                        "The server's certificate has changed since it was trusted. If you did not change it " +
                            "yourself, stop here: something between this phone and the server may be intercepting it."
                    } else {
                        "The server's certificate is not trusted yet. Compare the fingerprint with the one your " +
                            "server prints (openssl x509 -fingerprint -sha256) before trusting it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text("SHA-256 " + probe.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Issued to " + probe.subject, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { trustPending() }) {
                    Text(if (changed) "Trust the new certificate" else "Trust this certificate")
                }
            }
            if (pending == null && pinned != null) {
                Text("Trusted certificate SHA-256 $pinned", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { hostPort?.let(store::forget); pinVersion++; status = null }) {
                    Text("Forget trusted certificate")
                }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
