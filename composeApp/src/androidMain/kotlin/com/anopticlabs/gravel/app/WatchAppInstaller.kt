package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.network.readBody
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A release that claims more than this is not downloaded; the watch app's PBW is far smaller. */
const val MAX_WATCH_APP_PBW_BYTES = 1024L * 1024

enum class WatchAppInstallResult { INSTALLED, DOWNLOAD_FAILED, CHECK_FAILED, INSTALL_FAILED }

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val VERSION_LABEL = Regex("[0-9A-Za-z.+-]{1,32}")

/** Where a release file is: the screens say the download comes from GitHub. */
const val WATCH_APP_RELEASE_URL_START = "https://github.com/"

/** Whether [this] can be downloaded and checked: a short version label, a GitHub https address, a size within the cap and a whole lowercase digest. */
fun WatchAppRelease.isWellFormed(): Boolean =
    VERSION_LABEL.matches(version) && url.startsWith(WATCH_APP_RELEASE_URL_START) && sizeBytes in 1..MAX_WATCH_APP_PBW_BYTES && SHA256_HEX.matches(sha256)

/**
 * Downloads a Notebook watch app release and hands it to [sideload], which stores it in the locker. [sideload] is the
 * first code to parse the download, and it is called only when the download's length and SHA-256 are the release's.
 */
class WatchAppInstaller(
    private val httpClient: HttpClient,
    private val stagingDir: File,
    private val sideload: suspend (Path) -> Boolean,
    private val downloadLimit: Duration = 60.seconds,
) {
    private val oneAtATime = Mutex()

    suspend fun install(release: WatchAppRelease): WatchAppInstallResult = oneAtATime.withLock {
        if (!release.isWellFormed()) return WatchAppInstallResult.CHECK_FAILED
        val bytes = try {
            withTimeoutOrNull(downloadLimit) { download(release) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return WatchAppInstallResult.DOWNLOAD_FAILED
        if (bytes.size.toLong() != release.sizeBytes || sha256Hex(bytes) != release.sha256) return WatchAppInstallResult.CHECK_FAILED
        withContext(Dispatchers.IO) {
            val staged = File(stagingDir, "notebook.pbw")
            try {
                stagingDir.mkdirs()
                staged.writeBytes(bytes)
                if (sideload(Path(staged.absolutePath))) WatchAppInstallResult.INSTALLED else WatchAppInstallResult.INSTALL_FAILED
            } catch (e: IOException) {
                WatchAppInstallResult.INSTALL_FAILED
            } finally {
                staged.delete()
            }
        }
    }

    /** The body, read until it ends or is longer than the release; null when the answer is not a success. */
    private suspend fun download(release: WatchAppRelease): ByteArray? =
        httpClient.prepareGet(release.url).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            response.readBody { channel ->
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (body.size() <= release.sizeBytes) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    body.write(buffer, 0, read)
                }
                body.toByteArray()
            }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
