package com.anopticlabs.gravel.firmware

import coredevices.pebble.firmware.firmwareUpdateNotificationBody
import coredevices.pebble.firmware.testFwVersion
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FirmwareUpdateNotificationBodyTest {

    private fun update(notes: String) = FirmwareUpdateCheckResult.FoundUpdate(
        version = testFwVersion("v4.38.1"),
        url = "https://github.com/coredevices/PebbleOS/releases/download/v4.38.1/normal_obelix_pvt_v4.38.1.pbz",
        notes = notes,
    )

    @Test
    fun namesTheVersionAndTheWatch() {
        assertEquals(
            "PebbleOS v4.38.1 is available for Pebble Time 2",
            firmwareUpdateNotificationBody(update(notes = ""), "Pebble Time 2"),
        )
    }

    @Test
    fun appendsReleaseNotesWhenTheSourceSendsThem() {
        assertEquals(
            "PebbleOS v4.38.1 is available for Pebble Time 2:\nBug fixes",
            firmwareUpdateNotificationBody(update(notes = "Bug fixes"), "Pebble Time 2"),
        )
    }
}
