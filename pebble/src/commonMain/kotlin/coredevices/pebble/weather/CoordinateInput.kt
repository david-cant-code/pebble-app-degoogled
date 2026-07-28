package coredevices.pebble.weather

import kotlin.math.abs
import kotlin.math.roundToLong

// Shape check before toDouble(): parseDouble alone also accepts scientific
// notation, hex floats, "NaN"/"Infinity", and f/d suffixes.
private val COORDINATE_REGEX = Regex("""[+-]?(\d+([.,]\d*)?|[.,]\d+)""")

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
internal fun formatCoordinate(value: Double): String {
    val scaled = (value * 10000).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    val whole = magnitude / 10000
    val fraction = (magnitude % 10000).toString().padStart(4, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}
