package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.network.EndlessResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WatchAppInstallerTest {
    private val stagingDir = File(Files.createTempDirectory("watch-app-install").toFile(), "staging")
    private val pbw = ByteArray(40_000) { (it % 251).toByte() }
    private val requests = mutableListOf<String>()
    private val sideloaded = mutableListOf<ByteArray>()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(bytes: ByteArray = pbw, sizeBytes: Long = bytes.size.toLong(), url: String = "https://github.com/example/notebook.pbw") =
        WatchAppRelease("0.1", sha256Hex(bytes), sizeBytes, url)

    private fun installer(
        sideloads: Boolean = true,
        downloadLimit: Duration = 30.seconds,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = WatchAppInstaller(
        httpClient = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            answer(request)
        }),
        stagingDir = stagingDir,
        sideload = { path: Path ->
            sideloaded += File(path.toString()).readBytes()
            sideloads
        },
        downloadLimit = downloadLimit,
    )

    private fun serving(bytes: ByteArray, sideloads: Boolean = true) =
        installer(sideloads) { respond(ByteReadChannel(bytes), HttpStatusCode.OK) }

    private fun nothingStaged() = stagingDir.listFiles().orEmpty().isEmpty()

    @Test
    fun aDownloadThatMatchesThePinIsSideloadedAndItsStagedFileRemoved() = runBlocking {
        assertEquals(WatchAppInstallResult.INSTALLED, serving(pbw).install(release()))
        assertEquals(listOf("https://github.com/example/notebook.pbw"), requests)
        assertEquals(1, sideloaded.size)
        assertContentEquals(pbw, sideloaded.single())
        assertTrue(nothingStaged())
    }

    @Test
    fun anEndlessBodyFailsTheCheckAndLeavesNoCopierRunning() = runBlocking {
        val server = EndlessResponse()
        val installer = WatchAppInstaller(server.client, stagingDir, sideload = { true }, downloadLimit = 10.seconds)
        assertEquals(WatchAppInstallResult.CHECK_FAILED, installer.install(release()))
        server.assertNoCopierLeft()
    }

    @Test
    fun otherBytesOfTheSameLengthAreNotSideloaded() = runBlocking {
        val other = pbw.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(other).install(release()))
        assertTrue(sideloaded.isEmpty())
        assertTrue(nothingStaged())
    }

    @Test
    fun aLongerOrShorterDownloadIsNotSideloaded() = runBlocking {
        assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(pbw + 0).install(release()))
        assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(pbw.copyOf(pbw.size - 1)).install(release()))
        assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(ByteArray(0)).install(release()))
        // The served file has the pin's digest; only the pin's size differs from it.
        assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(pbw).install(release(pbw, pbw.size + 1L)))
        assertTrue(sideloaded.isEmpty())
        assertTrue(nothingStaged())
    }

    @Test
    fun aDownloadThatNeverEndsIsGivenUpOncePastThePinnedSize() = runBlocking {
        val endless = ByteChannel(autoFlush = true)
        val writer = launch(Dispatchers.IO) {
            try {
                while (true) {
                    endless.writeFully(ByteArray(16 * 1024))
                    delay(1.milliseconds)
                }
            } catch (e: IOException) {
                // The installer stopped reading and the channel closed under the writer.
            }
        }
        // Reading on to the download limit would end as DOWNLOAD_FAILED.
        val result = installer { respond(endless, HttpStatusCode.OK) }.install(release())
        writer.cancelAndJoin()
        assertEquals(WatchAppInstallResult.CHECK_FAILED, result)
        assertTrue(sideloaded.isEmpty())
    }

    @Test
    fun aRefusedOrBrokenDownloadIsADownloadFailure() = runBlocking {
        assertEquals(WatchAppInstallResult.DOWNLOAD_FAILED, installer { respond(ByteReadChannel(pbw), HttpStatusCode.NotFound) }.install(release()))
        assertEquals(WatchAppInstallResult.DOWNLOAD_FAILED, installer { respond(ByteReadChannel(pbw), HttpStatusCode.Found) }.install(release()))
        assertEquals(WatchAppInstallResult.DOWNLOAD_FAILED, installer { throw IOException("no route") }.install(release()))
        assertEquals(WatchAppInstallResult.DOWNLOAD_FAILED, installer { throw IllegalStateException("redirect loop") }.install(release()))
        assertEquals(WatchAppInstallResult.DOWNLOAD_FAILED, installer(downloadLimit = 200.milliseconds) { awaitCancellation() }.install(release()))
        assertTrue(sideloaded.isEmpty())
        assertTrue(nothingStaged())
    }

    @Test
    fun aSideloadThatFailsIsAnInstallFailureAndLeavesNothingStaged() = runBlocking {
        assertEquals(WatchAppInstallResult.INSTALL_FAILED, serving(pbw, sideloads = false).install(release()))
        assertEquals(1, sideloaded.size)
        assertTrue(nothingStaged())
    }

    @Test
    fun aReleaseThatIsNotWellFormedIsNotRequested() = runBlocking {
        val good = release()
        assertTrue(good.isWellFormed())
        val malformed = listOf(
            good.copy(url = "http://github.com/example/notebook.pbw"),
            good.copy(url = "https://example.invalid/notebook.pbw"),
            good.copy(url = "https://github.com.example.invalid/notebook.pbw"),
            good.copy(url = "notebook.pbw"),
            good.copy(sizeBytes = 0),
            good.copy(sizeBytes = MAX_WATCH_APP_PBW_BYTES + 1),
            good.copy(sha256 = good.sha256.uppercase()),
            good.copy(sha256 = good.sha256.dropLast(1)),
            good.copy(sha256 = ""),
            good.copy(version = " "),
            good.copy(version = ""),
            good.copy(version = "0.1 "),
            good.copy(version = "1".repeat(33)),
        )
        for (release in malformed) {
            assertFalse(release.isWellFormed(), "$release")
            assertEquals(WatchAppInstallResult.CHECK_FAILED, serving(pbw).install(release), "$release")
        }
        assertTrue(requests.isEmpty())
        assertTrue(sideloaded.isEmpty())
    }

    @Test
    fun everyShippedReleaseIsWellFormedAndListedOnce() {
        for (release in SHIPPED_WATCH_APP_RELEASES) assertTrue(release.isWellFormed(), "$release")
        assertEquals(SHIPPED_WATCH_APP_RELEASES.size, SHIPPED_WATCH_APP_RELEASES.map { it.sha256 }.toSet().size)
        assertEquals(SHIPPED_WATCH_APP_RELEASES.size, SHIPPED_WATCH_APP_RELEASES.map { it.version }.toSet().size)
    }

    @Test
    fun everyShippedReleaseUrlIsTheWatchAppReleaseFileForItsVersion() {
        for (release in SHIPPED_WATCH_APP_RELEASES) {
            assertEquals("https://github.com/david-cant-code/gravel-watchapp/releases/download/${release.version}/notebook.pbw", release.url)
        }
    }
}
