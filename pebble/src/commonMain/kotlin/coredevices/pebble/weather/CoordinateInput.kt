package coredevices.pebble.weather

import coredevices.database.WeatherLocationEntity
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

// Shape check before toDouble(): parseDouble alone also accepts scientific
// notation, hex floats, "NaN"/"Infinity", and f/d suffixes. [0-9] rather than
// \d because Android's ICU regex matches all Unicode digits with \d.
private val COORDINATE_REGEX = Regex("""[+-]?([0-9]+([.,][0-9]*)?|[.,][0-9]+)""")

fun parseLatitude(text: String): Double? = parseCoordinate(text, bound = 90.0)

fun parseLongitude(text: String): Double? = parseCoordinate(text, bound = 180.0)

/**
 * Parses a user-typed coordinate as plain decimal degrees, accepting ',' as
 * the decimal separator for comma-decimal locales. Null unless it's a plain
 * decimal number within [-bound, bound].
 */
private fun parseCoordinate(text: String, bound: Double): Double? {
    val trimmed = text.trim()
    if (!COORDINATE_REGEX.matches(trimmed)) return null
    val value = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
    return if (value in -bound..bound) value else null
}

/**
 * Default display name for a manually entered location, e.g. "48.8566, 2.3522",
 * rounded to 4 decimal places (~11 m).
 */
fun coordinateDisplayName(latitude: Double, longitude: Double): String =
    "${formatCoordinate(latitude)}, ${formatCoordinate(longitude)}"

// Hand-rolled because common Kotlin has no locale-independent fixed-point
// formatter and Double.toString() can yield scientific notation (e.g. 1.0E-4).
// Always emits a fractional part: the URL coordinate scrubber needs a uniform
// digits-dot-digits shape.
internal fun formatCoordinate(value: Double): String {
    val scaled = (value * 10000).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    val whole = magnitude / 10000
    val fraction = (magnitude % 10000).toString().padStart(4, '0').trimEnd('0').ifEmpty { "0" }
    return "$sign$whole.$fraction"
}

// The firmware weather db caps a location entry at 64 bytes; 60 bytes of name
// leaves room for the string headers.
internal const val MAX_LOCATION_NAME_UTF8_BYTES = 60

/**
 * Cleans a user-typed location name for storage and the watch record: strips
 * control characters ('\n' is the structural separator inside timeline pin
 * strings) and caps the UTF-8 byte length without splitting a surrogate pair.
 */
fun sanitizeLocationName(raw: String): String {
    val cleaned = raw.filterNot { it.isISOControl() }.trim()
    val out = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < cleaned.length) {
        val c = cleaned[index]
        val chunk = if (c.isHighSurrogate() && index + 1 < cleaned.length && cleaned[index + 1].isLowSurrogate()) {
            cleaned.substring(index, index + 2)
        } else {
            c.toString()
        }
        val size = chunk.encodeToByteArray().size
        if (bytes + size > MAX_LOCATION_NAME_UTF8_BYTES) break
        out.append(chunk)
        bytes += size
        index += chunk.length
    }
    return out.toString().trim()
}

/**
 * Builds the entity for a manually entered fixed location. A blank (or
 * sanitized-to-blank) name falls back to the formatted coordinates.
 */
fun manualWeatherLocation(
    latitude: Double,
    longitude: Double,
    rawName: String,
    orderIndex: Int,
): WeatherLocationEntity = WeatherLocationEntity(
    key = Uuid.random(),
    name = sanitizeLocationName(rawName).ifEmpty { coordinateDisplayName(latitude, longitude) },
    latitude = latitude,
    longitude = longitude,
    currentLocation = false,
    orderIndex = orderIndex,
)
