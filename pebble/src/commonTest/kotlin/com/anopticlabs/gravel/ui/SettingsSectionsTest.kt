package com.anopticlabs.gravel.ui

import coredevices.pebble.ui.FLAKY_NETWORK_MODE_NOTE
import coredevices.pebble.ui.serverOnMobileDataNote
import coredevices.pebble.ui.showsFlakyNetworkMode
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSectionsTest {

    @Test
    fun theMobileDataSwitchShowsInTheServerModesWithWhatHappensOffWifi() {
        for (mode in CactusSTTMode.entries) {
            for (hasModel in listOf(true, false)) {
                val note = serverOnMobileDataNote(mode, hasModel)
                val expected = when {
                    mode == CactusSTTMode.RemoteOnly -> "watch dictation doesn't work."
                    mode != CactusSTTMode.RemoteFirst && mode != CactusSTTMode.LocalFirst -> null
                    hasModel -> "watch dictation is transcribed on this phone."
                    else -> "watch dictation doesn't work until this phone has a speech model."
                }
                if (expected == null) assertEquals(null, note, "$mode") else assertTrue(note!!.endsWith(expected), "$mode $hasModel: $note")
            }
        }
    }

    @Test
    fun flakyNetworkModeShowsInTheModesWithAServerAndAPhoneFallbackWithAModel() {
        val shown = CactusSTTMode.entries.filter { showsFlakyNetworkMode(it, phoneHasModel = true) }.toSet()
        assertEquals(setOf(CactusSTTMode.RemoteFirst, CactusSTTMode.LocalFirst), shown)
        assertTrue(CactusSTTMode.entries.none { showsFlakyNetworkMode(it, phoneHasModel = false) })
    }

    @Test
    fun flakyNetworkModeNamesTheWatchsOwnDeadline() {
        assertTrue(FLAKY_NETWORK_MODE_NOTE.contains("within ${PEBBLE_FW_TRANSCRIPTION_TIMEOUT.inWholeSeconds} seconds"))
    }
}
