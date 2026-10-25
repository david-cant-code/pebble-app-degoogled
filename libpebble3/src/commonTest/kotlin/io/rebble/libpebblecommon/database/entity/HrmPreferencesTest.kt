package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue.Companion.asBytes
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun fw(minor: Int, patch: Int) = FirmwareVersion(
    stringVersion = "v4.$minor.$patch",
    timestamp = Instant.DISTANT_PAST,
    major = 4,
    minor = minor,
    patch = patch,
    suffix = null,
    gitHash = "",
    isRecovery = false,
    isDualSlot = false,
    isSlot0 = false,
)

class HrmPreferencesTest {
    @Test
    fun fiveMinSupportedFromV4_39() {
        assertTrue(HRMonitoringInterval.FiveMin.isSupportedBy(fw(39, 0)))
        assertFalse(HRMonitoringInterval.FiveMin.isSupportedBy(fw(38, 2)))
        assertTrue(HRMonitoringInterval.TenMin.isSupportedBy(fw(38, 2)))
    }

    @Test
    fun fiveMinIsSentAsTenMinToOlderFirmware() {
        val fiveMin = HrmPreferencesValue(measurementInterval = HRMonitoringInterval.FiveMin)
        assertEquals(HRMonitoringInterval.TenMin.value.toUByte(), fiveMin.asBytes(fw(38, 2))[1])
        assertEquals(HRMonitoringInterval.FiveMin.value.toUByte(), fiveMin.asBytes(fw(39, 0))[1])
        val thirtyMin = HrmPreferencesValue(measurementInterval = HRMonitoringInterval.ThirtyMin)
        assertEquals(HRMonitoringInterval.ThirtyMin.value.toUByte(), thirtyMin.asBytes(fw(38, 2))[1])
    }

    @Test
    fun unknownWatchValueFallsBackToTenMin() {
        assertEquals(HRMonitoringInterval.FiveMin, HRMonitoringInterval.fromInt(4))
        assertEquals(HRMonitoringInterval.TenMin, HRMonitoringInterval.fromInt(42))
    }
}
