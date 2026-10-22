package gravel.ring.records

import coredevices.haversine.DataDecodeException
import gravel.ring.audio.DecodedAudio
import gravel.ring.audio.decodeAudioRecord
import gravel.ring.u16le
import gravel.ring.u32le
import kotlin.time.Instant

internal class ParsedCollection(
    val audio: DecodedAudio?,
    val startIndex: Long?,
    val multiPart: Boolean,
    val finalPart: Boolean,
    val sawGroup: Boolean,
    val unixSeconds: Long?,
    val pressBitmask: Long?,
    val pressCount: Long?,
    val lifetimeCount: Long?,
    val batteryMillivolts: Int?,
    /** Raw platform-version bytes from tag `0x25`. Layout is unverified. */
    val platformVersion: ByteArray?,
    val deviceId: ByteArray?,
)

internal fun parseCollection(bytes: ByteArray): ParsedCollection {
    if (bytes.size < 4) throw DataDecodeException("collection shorter than a header", bytes)
    val (origin, declared) = header(bytes)
    if (declared < origin || declared > bytes.size) {
        throw DataDecodeException("declared length $declared", bytes)
    }
    var audio: DecodedAudio? = null
    var startIndex: Long? = null
    var multiPart = false
    var finalPart = true
    var sawGroup = false
    var clock: Long? = null
    var offset: Long? = null
    var bitmask: Long? = null
    var presses: Long? = null
    var lifetime: Long? = null
    var battery: Int? = null
    var versions: ByteArray? = null
    var deviceId: ByteArray? = null
    var cursor = origin
    while (cursor < declared) {
        if (cursor >= declared) break
        val tag = bytes[cursor].toInt() and 0xFF
        val wide = tag == 0x50 || tag == 0x51
        val width = if (wide) 4 else 2
        if (cursor + 1 + width > declared) throw DataDecodeException("record header overruns", bytes)
        val length = if (wide) bytes.u32le(cursor + 1) else bytes.u16le(cursor + 1).toLong()
        val payloadAt = cursor + 1 + width
        if (length < 0 || payloadAt + length > declared) {
            throw DataDecodeException("record 0x${tag.toString(16)} length $length", bytes)
        }
        val payloadEnd = payloadAt + length.toInt()
        val payload = bytes.copyOfRange(payloadAt, payloadEnd)
        when (tag) {
            0x51 -> audio = appendAudio(audio, decodeWrapped(payload, bytes))
            0x50 -> audio = appendAudio(audio, decodePcm(payload, bytes))
            0x52 -> {
                if (payload.size < 6) throw DataDecodeException("short group record", bytes)
                startIndex = payload.u32le(0)
                multiPart = payload[4].toInt() and 0xFF != 0
                finalPart = payload[5].toInt() and 0xFF != 0
                sawGroup = true
            }
            0x02 -> {
                if (payload.size < 4) throw DataDecodeException("short clock", bytes)
                clock = payload.u32le(0)
            }
            0x35 -> {
                if (payload.size < 4) throw DataDecodeException("short clock offset", bytes)
                offset = payload.u32le(0)
            }
            0x53 -> {
                if (payload.size < 8) throw DataDecodeException("short gesture", bytes)
                bitmask = payload.u32le(0)
                presses = payload.u32le(4)
            }
            0x54 -> {
                if (payload.size < 4) throw DataDecodeException("short lifetime count", bytes)
                lifetime = payload.u32le(0)
            }
            0x37 -> {
                if (payload.size < 2) throw DataDecodeException("short battery", bytes)
                battery = payload.u16le(0)
            }
            0x25 -> versions = payload
            0x01 -> deviceId = payload
        }
        cursor = payloadEnd
    }
    return ParsedCollection(
        audio = audio,
        startIndex = startIndex,
        multiPart = multiPart,
        finalPart = if (sawGroup) finalPart else true,
        sawGroup = sawGroup,
        unixSeconds = collectionUnix(clock, offset),
        pressBitmask = bitmask,
        pressCount = presses,
        lifetimeCount = lifetime,
        batteryMillivolts = battery,
        platformVersion = versions,
        deviceId = deviceId,
    )
}

/** `(tag 0x02 + tag 0x35 + 1) mod 2^32`. Null when either tag is missing. */
internal fun collectionUnix(clock: Long?, offset: Long?): Long? {
    if (clock == null || offset == null) return null
    return (clock + offset + 1L) and 0xFFFF_FFFFL
}

internal fun collectionInstant(seconds: Long?): Instant? =
    seconds?.let { Instant.fromEpochSeconds(it) }

/**
 * Only the 24-bit little-endian header (byte 3 == 0x00) was seen on hardware.
 * The other two forms are the ones the spec says the library also accepts.
 */
private fun header(bytes: ByteArray): Pair<Int, Int> = when {
    bytes[3] == 0.toByte() -> {
        val length = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16)
        4 to length
    }
    bytes[0] == 0xFF.toByte() -> {
        val length = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        3 to length
    }
    else -> {
        val length = ((bytes[0].toInt() and 0xFF) shl 16) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            (bytes[2].toInt() and 0xFF)
        3 to length
    }
}

private fun decodeWrapped(payload: ByteArray, collection: ByteArray): DecodedAudio = try {
    decodeAudioRecord(payload)
} catch (e: DataDecodeException) {
    throw DataDecodeException(e.message ?: "audio", collection)
}

/**
 * ASSUMPTION: tag `0x50` is a u32 rate followed by little-endian i16 samples
 * and is already signed. The spec says this form was never seen.
 */
private fun decodePcm(payload: ByteArray, collection: ByteArray): DecodedAudio {
    if (payload.size < 4 || (payload.size - 4) % 2 != 0) {
        throw DataDecodeException("pcm payload", collection)
    }
    val rate = payload.u32le(0).toUInt()
    val samples = ShortArray((payload.size - 4) / 2)
    for (i in samples.indices) {
        val at = 4 + i * 2
        samples[i] = ((payload[at].toInt() and 0xFF) or (payload[at + 1].toInt() shl 8)).toShort()
    }
    return DecodedAudio(samples, rate)
}

private fun appendAudio(existing: DecodedAudio?, next: DecodedAudio): DecodedAudio {
    if (existing == null) return next
    return DecodedAudio(existing.samples + next.samples, next.sampleRate)
}

/**
 * ASSUMPTION: tag `0x53`'s count is a running total and bit i (i < 32) says
 * press i was a long press. Presses since [previousCount] become
 * space-separated `short` / `long` words. A backwards count restarts at 0.
 */
internal fun buttonWords(bitmask: Long, count: Long, previousCount: Long): Pair<String?, Long> {
    var from = previousCount
    if (count < from) from = 0
    val added = (count - from).toInt().coerceAtMost(32)
    if (added <= 0) return null to count
    val words = buildList {
        for (i in 0 until added) {
            val press = from + i
            val long = press < 32 && (bitmask and (1L shl press.toInt())) != 0L
            add(if (long) "long" else "short")
        }
    }
    return words.joinToString(" ") to count
}

/**
 * ASSUMPTION: a multi-part group is audio. A single part is audio when it
 * lasts at least 1.0 s. The spec's advice was to drop items under about 1 s.
 */
internal fun isAudioGroup(multiPart: Boolean, samples: Int, sampleRate: UInt): Boolean {
    if (multiPart) return true
    if (sampleRate == 0u) return false
    return samples.toDouble() / sampleRate.toDouble() >= 1.0
}

/**
 * ASSUMPTION (unverified on hardware): four bytes `fwMajor, fwMinor, fwPatch,
 * hwMajor`, hardware minor 0. A printable ASCII payload is used as the
 * firmware string instead. TODO: compare with a ring shown as hw 11.0 / fw 3.75.
 */
internal class PlatformVersion(
    val firmware: String,
    val hardware: Pair<Int, Int>,
)

internal fun parsePlatformVersion(bytes: ByteArray): PlatformVersion? {
    if (bytes.isEmpty()) return null
    if (bytes.all { it in 0x20..0x7E }) {
        val text = bytes.decodeToString()
        return PlatformVersion(text, 0 to 0)
    }
    if (bytes.size < 4) return null
    val firmware = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}"
    return PlatformVersion(firmware, (bytes[3].toInt() and 0xFF) to 0)
}

/**
 * ASSUMPTION: six bytes are a Bluetooth MAC, formatted `AA:BB:CC:DD:EE:FF`.
 * TODO: compare with the address the phone bonded and the serial on the box.
 */
internal fun formatHardwareSerial(bytes: ByteArray): String? {
    if (bytes.size < 6) return null
    return bytes.take(6).joinToString(":") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
}

/**
 * ASSUMPTION: programmed serial is printable ASCII, or absent when the
 * payload is empty or all 0x00 / 0xFF. TODO: compare with the boxed serial.
 */
internal fun formatProgrammedSerial(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    if (bytes.all { it == 0.toByte() || it == 0xFF.toByte() }) return null
    if (bytes.any { it < 0x20 || it > 0x7E }) return null
    return bytes.decodeToString()
}
