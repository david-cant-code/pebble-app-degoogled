package coredevices.pebble.ui

import CoreNav
import DocumentAttachment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.back
import coredevices.pebble.services.ContactAttachment
import coredevices.pebble.services.ContactDeveloperApi
import coredevices.pebble.services.ContactResult
import coredevices.ui.SignInDialog
import coredevices.util.FORK_ISSUE_TRACKER_URL
import coredevices.util.Platform
import coredevices.util.isIOS
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import rememberOpenDocumentLauncher
import rememberOpenPhotoLauncher

private const val MAX_BODY = 5000
private const val MAX_ATTACHMENTS = 3
private const val MAX_ATTACHMENT_BYTES = 2L * 1024 * 1024

// Fork: where the permanently-signed-out state sends people instead of the
// dead sign-in dialog; the tracker URL is the shared constant in util.

@Composable
fun ContactDeveloperScreen(
    coreNav: CoreNav,
    appId: String,
    appTitle: String,
) {
    val api = koinInject<ContactDeveloperApi>()
    val platform = koinInject<Platform>()
    val scope = rememberCoroutineScope()
    val logger = remember { Logger.withTag("ContactDeveloperScreen") }

    val signedIn by Firebase.auth.authStateChanged
        .map { it != null }
        .collectAsState(Firebase.auth.currentUser != null)

    var showSignInDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ContactAttachment>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun handlePicked(picked: List<DocumentAttachment>?) {
        if (picked == null) return
        val capped = picked.take(MAX_ATTACHMENTS)
        // The picker's reported size can be unreliable on Android (uses
        // InputStream.available()); read bytes eagerly so we can enforce
        // the 2 MB cap on the actual content, and so retries reuse the data.
        val prepared = mutableListOf<ContactAttachment>()
        for (doc in capped) {
            val bytes = try {
                doc.source.use { it.readByteArray() }
            } catch (e: Exception) {
                logger.w(e) { "Failed to read picked attachment: ${doc.fileName}" }
                error = "Could not read \"${doc.fileName}\"."
                return
            }
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                error = "Attachment \"${doc.fileName}\" exceeds 2 MB."
                return
            }
            prepared += ContactAttachment(
                fileName = doc.fileName,
                mimeType = doc.mimeType,
                bytes = bytes,
            )
        }
        error = null
        attachments = prepared
    }

    val pickAttachments = rememberOpenDocumentLauncher(::handlePicked)
    val pickPhotos = if (platform.isIOS) {
        rememberOpenPhotoLauncher(::handlePicked)
    } else {
        null
    }

    fun submit() {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            error = "Please enter a message."
            return
        }
        if (trimmed.length > MAX_BODY) {
            error = "Message is too long (${trimmed.length}/$MAX_BODY)."
            return
        }
        sending = true
        error = null
        scope.launch {
            val result = try {
                api.sendMessage(appId, trimmed, attachments)
            } catch (e: Exception) {
                logger.e(e) { "sendMessage threw" }
                ContactResult.NetworkError
            }
            when (result) {
                ContactResult.Success -> sent = true
                ContactResult.NotSignedIn -> error = "Please sign in again."
                ContactResult.EmailNotVerified ->
                    error = "Your email address must be verified before contacting developers."
                is ContactResult.NotContactable -> error = result.message
                is ContactResult.RateLimited -> error = result.message
                is ContactResult.BadRequest -> error = result.message
                ContactResult.NetworkError -> error = "Network error. Please try again."
                is ContactResult.ServerError -> error = result.message
            }
            sending = false
        }
    }

    if (showSignInDialog) {
        SignInDialog(onDismiss = { showSignInDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact the developer") },
                navigationIcon = {
                    IconButton(
                        onClick = coreNav::goBack,
                        enabled = !sending,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Fork: the intro only makes sense when a message can actually
                // be sent, which needs the Core-account sign-in this build
                // removed; signedIn is permanently false against the auth stub.
                if (signedIn) {
                    Text(
                        "Send a message about $appTitle. The developer will receive it via email and can reply to you. Your email address is not shared with them.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                when {
                    sent -> {
                        Text(
                            "Message sent. The developer will receive an email and may reply to you directly.",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = coreNav::goBack,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Close")
                        }
                    }

                    !signedIn -> {
                        // Fork: this branch is permanently taken (the auth stub
                        // never signs in), and upstream's remedy here, a sign-in
                        // dialog, can only offer disabled providers in this
                        // build. Point people at this fork's own issue tracker
                        // instead: a problem seen on this unofficial build
                        // should not land on the watchface developer or on the
                        // upstream app's support channels.
                        val uriHandler = LocalUriHandler.current
                        Text(
                            "Contacting developers through a Core account is not available in this unofficial build.",
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Report any problem with $appTitle, or with this app, on this build's issue tracker rather than to the app's developer: this build's changes may be the cause, and the developer should not get reports from an unofficial build they have never seen.",
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { uriHandler.openUri(FORK_ISSUE_TRACKER_URL) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open the issue tracker on GitHub")
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { if (it.length <= MAX_BODY) message = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            label = { Text("Message") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                            enabled = !sending,
                        )
                        Text(
                            "${MAX_BODY - message.length} characters remaining",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Attach up to $MAX_ATTACHMENTS files, 2 MB each (optional)",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { pickAttachments(listOf("*/*")) },
                                enabled = !sending && attachments.size < MAX_ATTACHMENTS,
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            ) {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Add files")
                            }
                            if (pickPhotos != null) {
                                OutlinedButton(
                                    onClick = { pickPhotos() },
                                    enabled = !sending && attachments.size < MAX_ATTACHMENTS,
                                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                ) {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                    Text("Add photos")
                                }
                            }
                        }
                        attachments.forEachIndexed { index, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "${file.fileName} — ${file.bytes.size / 1024} KB",
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .padding(start = 4.dp),
                                )
                                Spacer(Modifier.size(0.dp))
                                IconButton(
                                    onClick = {
                                        attachments =
                                            attachments.toMutableList().also { it.removeAt(index) }
                                    },
                                    enabled = !sending,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        if (error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                error!!,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = ::submit,
                            enabled = !sending && message.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Send message")
                            }
                        }
                    }
                }
            }
        }
    }
}
