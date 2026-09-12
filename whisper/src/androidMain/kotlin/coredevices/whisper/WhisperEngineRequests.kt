package coredevices.whisper

import coredevices.whisper.WhisperEngineProtocol.MAX_MESSAGE_BYTES

/**
 * The pure decisions [WhisperEngineService] makes over what it receives
 * and what it writes back, kept apart from the Parcel code so the host
 * suite can drive them: the app-side counterparts are in
 * WhisperEngineReplies.kt. The process predicate lives here too, since
 * the engine process is the one that must answer it.
 */

/**
 * Whether [samples] native-order floats fit in a region of
 * [regionBytes]: the sample count arrives separately from the region,
 * so a count past the region's end would read past the mapping.
 */
internal fun audioFitsRegion(samples: Int, regionBytes: Int): Boolean =
    samples >= 0 && samples.toLong() * Float.SIZE_BYTES <= regionBytes

/**
 * A failure message as the bytes the reply carries, cut at the
 * protocol's message bound so the client's check never refuses a reply
 * this process meant to be read.
 */
internal fun boundedMessageBytes(message: String): ByteArray =
    message.toByteArray().let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES) else it }

/**
 * Whether [uid] is one the platform reserves for isolated processes:
 * AOSP `android.os.Process`, `FIRST_ISOLATED_UID` to `LAST_ISOLATED_UID`
 * within each user's block of 100000.
 */
fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 99_000..99_999
