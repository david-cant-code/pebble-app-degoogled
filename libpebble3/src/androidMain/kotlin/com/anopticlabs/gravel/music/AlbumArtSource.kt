package com.anopticlabs.gravel.music

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Upper bound on the size an album art decode aims for, whatever size the watch asks for. */
internal const val MAX_ALBUM_ART_TARGET_PX = 512

/** Longest side, in pixels, an album art decode may produce. */
internal const val MAX_ALBUM_ART_DECODED_PX = 2 * MAX_ALBUM_ART_TARGET_PX

/** Largest artwork file read from a media app's URI. */
internal const val MAX_ALBUM_ART_BYTES = 4 * 1024 * 1024

/** Largest artwork, in source pixels, decoded from a media app's URI. */
internal const val MAX_ALBUM_ART_SOURCE_PIXELS = 4096L * 4096

internal val ALBUM_ART_READ_TIMEOUT = 5.seconds

/**
 * Whether a media app's artwork URI may be opened: content URIs only, and none served by this
 * app's own providers. The authority's `userId@` prefix is dropped first, as provider lookup does
 * (android16-release, ContextImpl.ApplicationContentResolver.acquireProvider,
 * ContentProvider.getAuthorityWithoutUserId).
 */
internal fun isOpenableArtUri(scheme: String?, authority: String?, ownAuthorities: Set<String>): Boolean {
    if (scheme != "content" || authority == null) return false
    val provider = authority.substringAfterLast('@')
    return provider.isNotEmpty() && provider !in ownAuthorities
}

// The decoded authority, which is the one provider lookup resolves.
internal fun Uri.isOpenableArtUri(ownAuthorities: Set<String>): Boolean =
    isOpenableArtUri(scheme, authority, ownAuthorities)

/**
 * Every authority this app's providers declare, disabled providers included: the enabled check
 * passes every component under MATCH_DISABLED_COMPONENTS (android16-release,
 * PackageInfoUtils.generate, PackageUserStateUtils.isEnabled).
 */
fun ownProviderAuthorities(context: Context): Set<String> {
    val flags = PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, flags)
    }
    return info.providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }.filter { it.isNotEmpty() }.toSet()
}

/**
 * The power-of-two sample size at which a [width]x[height] image still covers a
 * [targetWidth]x[targetHeight] center crop (each target side raised to at least 1 and capped at
 * [MAX_ALBUM_ART_TARGET_PX]), or 1 for an image already smaller, raised further until the longer
 * decoded side is at most [MAX_ALBUM_ART_DECODED_PX]. Null for an image with no size or over
 * [MAX_ALBUM_ART_SOURCE_PIXELS].
 */
internal fun albumArtSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int? {
    if (width <= 0 || height <= 0) return null
    if (width.toLong() * height > MAX_ALBUM_ART_SOURCE_PIXELS) return null
    val goalWidth = targetWidth.coerceIn(1, MAX_ALBUM_ART_TARGET_PX)
    val goalHeight = targetHeight.coerceIn(1, MAX_ALBUM_ART_TARGET_PX)
    var sample = 1
    while (width / (sample * 2) >= goalWidth && height / (sample * 2) >= goalHeight) sample *= 2
    // Rounded up, as scaled decoders round a sampled side.
    while ((maxOf(width, height) + sample - 1) / sample > MAX_ALBUM_ART_DECODED_PX) sample *= 2
    return sample
}

/** The stream's bytes, or null once it runs past [limit]. */
internal fun readArtBytes(input: InputStream, limit: Int = MAX_ALBUM_ART_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}

/**
 * Opens and reads artwork from another app's provider under a deadline. The work runs on [scope]
 * and is not joined, because coroutine cancellation is cooperative (kotlinx.coroutines) and does
 * not interrupt a blocked binder call or read. One read runs at a time: a provider that never
 * answers keeps its slot until its work returns, and later reads wait for the slot within their
 * own deadline.
 */
internal class ArtUriReader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val timeout: Duration = ALBUM_ART_READ_TIMEOUT,
) {
    private val slot = Semaphore(1)

    /**
     * The bytes, or null when [open] returns null, throws, runs past the cap or the deadline. On
     * the deadline, or when the caller is cancelled, the work is cancelled and [abandon] runs.
     */
    suspend fun read(open: () -> InputStream?, abandon: () -> Unit): ByteArray? {
        var work: Deferred<ByteArray?>? = null
        var finished = false
        try {
            val result = withTimeoutOrNull(timeout) {
                slot.acquire()
                val started = scope.async(dispatcher) {
                    try {
                        // An open that returns after the deadline is closed here, unread.
                        open()?.use { if (isActive) readArtBytes(it) else null }
                    } catch (e: Exception) {
                        null
                    }
                }
                started.invokeOnCompletion { slot.release() }
                work = started
                Result.success(started.await())
            }
            finished = result != null
            return result?.getOrNull()
        } finally {
            if (!finished) {
                work?.cancel()
                abandon()
            }
        }
    }
}

/**
 * Opens a provider's artwork for [ArtUriReader]; [abandon] cancels an open still under way and
 * closes a stream already opened, so a blocked read fails instead of keeping its thread.
 */
internal class ProviderArtOpen(private val resolver: ContentResolver) {
    private val signal = CancellationSignal()
    private val stream = AtomicReference<InputStream?>()

    fun open(uri: Uri): InputStream? =
        resolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream()?.also(stream::set)

    fun abandon() {
        signal.cancel()
        runCatching { stream.get()?.close() }
    }
}

/** [uri]'s bytes if it passes [isOpenableArtUri]; [open] is never called for one that does not. */
internal suspend fun ArtUriReader.readArtUri(
    uri: Uri,
    ownAuthorities: Set<String>,
    open: (Uri) -> InputStream?,
    abandon: () -> Unit,
): ByteArray? {
    if (!uri.isOpenableArtUri(ownAuthorities)) return null
    return read({ open(uri) }, abandon)
}
