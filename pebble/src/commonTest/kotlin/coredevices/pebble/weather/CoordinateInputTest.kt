package coredevices.pebble.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinateInputTest {

    @Test
    fun parsesPlainDecimalDegrees() {
        assertEquals(48.8566, parseLatitude("48.8566"))
        assertEquals(-33.8688, parseLatitude("-33.8688"))
        assertEquals(2.3522, parseLongitude("2.3522"))
        assertEquals(-122.4194, parseLongitude("-122.4194"))
        assertEquals(7.0, parseLatitude("+7"))
        assertEquals(0.0, parseLatitude("0"))
    }

    @Test
    fun acceptsCommaDecimalSeparator() {
        assertEquals(48.8566, parseLatitude("48,8566"))
        assertEquals(-0.5, parseLongitude("-,5"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(51.5074, parseLatitude(" 51.5074 "))
    }

    @Test
    fun acceptsBareLeadingOrTrailingSeparator() {
        assertEquals(0.5, parseLatitude(".5"))
        assertEquals(5.0, parseLatitude("5."))
    }

    @Test
    fun enforcesRangeBounds() {
        assertEquals(90.0, parseLatitude("90"))
        assertEquals(-90.0, parseLatitude("-90"))
        assertNull(parseLatitude("90.0001"))
        assertNull(parseLatitude("-91"))
        assertEquals(180.0, parseLongitude("180"))
        assertEquals(-180.0, parseLongitude("-180"))
        assertNull(parseLongitude("180.1"))
        assertNull(parseLongitude("-200"))
        // A valid longitude is not automatically a valid latitude
        assertNull(parseLatitude("122.4194"))
    }

    @Test
    fun rejectsNonNumericInput() {
        assertNull(parseLatitude(""))
        assertNull(parseLatitude("  "))
        assertNull(parseLatitude("abc"))
        assertNull(parseLatitude("12.34.56"))
        assertNull(parseLatitude("12,34,56"))
        assertNull(parseLatitude("--5"))
        assertNull(parseLatitude("1 2"))
    }

    @Test
    fun rejectsParseDoubleExtras() {
        // All of these are accepted by String.toDoubleOrNull()
        assertNull(parseLatitude("1e2"))
        assertNull(parseLatitude("NaN"))
        assertNull(parseLatitude("Infinity"))
        assertNull(parseLatitude("-Infinity"))
        assertNull(parseLatitude("45f"))
        assertNull(parseLongitude("0x1p4"))
    }

    @Test
    fun formatsDisplayName() {
        assertEquals("48.8566, 2.3522", coordinateDisplayName(48.8566, 2.3522))
        assertEquals("-33.8688, 151.2093", coordinateDisplayName(-33.8688, 151.2093))
    }

    @Test
    fun displayNameRoundsToFourDecimals() {
        assertEquals("48.8566, 2.3522", coordinateDisplayName(48.85661234, 2.35219876))
    }

    @Test
    fun displayNameAvoidsScientificNotationAndTrailingZeros() {
        assertEquals("0.0001, 0", coordinateDisplayName(0.0001, 0.0))
        assertEquals("10, -0.5", coordinateDisplayName(10.0, -0.5))
        assertEquals("0, 0", coordinateDisplayName(-0.00001, 0.0))
    }
}
