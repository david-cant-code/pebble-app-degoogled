package coredevices.util.transcription

import io.ktor.client.HttpClient

// iOS stubs: this fork is Android-only and its iOS sources are unmaintained.
// The module still declares the iOS targets, so every commonMain `expect`
// needs an actual there for the module to compile; these refuse rather than
// pretend, and never hand out a client that would skip the certificate check.

actual suspend fun probeServerCertificate(host: String, port: Int): ServerCertificateProbe =
    throw UnsupportedOperationException("Self-hosted server trust is Android-only in this fork")

actual fun selfHostedHttpClient(hostPort: String, pinnedFingerprint: () -> String?): HttpClient =
    throw UnsupportedOperationException("Self-hosted server trust is Android-only in this fork")
