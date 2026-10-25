package com.anopticlabs.gravel.health

import io.rebble.libpebblecommon.database.entity.HealthSettingsEntry
import io.rebble.libpebblecommon.database.entity.UnitsDistanceValue
import io.rebble.libpebblecommon.database.entity.unitsSeedEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class UnitsSeedTest {
    @Test
    fun storedUnitsAreLeftAlone() {
        val stored = HealthSettingsEntry(id = "unitsDistance", value = """{"imperialUnits":false}""")
        assertNull(unitsSeedEntry(stored, imperialUnits = true))
    }

    @Test
    fun missingUnitsAreSeededAtTheEpoch() {
        listOf(true, false).forEach { imperial ->
            val seeded = unitsSeedEntry(existing = null, imperialUnits = imperial)!!
            assertEquals("unitsDistance", seeded.id)
            assertEquals(imperial, UnitsDistanceValue.fromString(seeded.value)?.imperialUnits)
            assertEquals(Instant.fromEpochMilliseconds(0), seeded.timestamp.instant)
        }
    }
}
