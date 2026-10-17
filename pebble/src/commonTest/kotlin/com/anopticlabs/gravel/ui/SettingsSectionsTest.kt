package com.anopticlabs.gravel.ui

import coredevices.pebble.ui.FLAKY_NETWORK_MODE_NOTE
import coredevices.pebble.ui.Section
import coredevices.pebble.ui.offlineAccuracyNote
import coredevices.pebble.ui.parent
import coredevices.pebble.ui.serverOnMobileDataNote
import coredevices.pebble.ui.showsFlakyNetworkMode
import coredevices.pebble.ui.topLevelSections
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.voice.PEBBLE_FW_TRANSCRIPTION_TIMEOUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSectionsTest {

    @Test
    fun speechRecognitionIsListedUnderAiNotAtTheTopLevel() {
        val shown = topLevelSections(setOf(Section.General, Section.AI, Section.Speech))

        assertEquals(Section.AI, Section.Speech.parent())
        assertTrue(Section.AI in shown)
        assertFalse(Section.Speech in shown)
    }

    @Test
    fun aiTakesSpeechRecognitionsPlaceInTheList() {
        val shown = topLevelSections(setOf(Section.Health, Section.AI, Section.Display))

        assertEquals(listOf(Section.Battery, Section.Health, Section.AI, Section.Display), shown)
    }

    @Test
    fun aSectionWithNoVisibleItemStaysHidden() {
        assertFalse(Section.AI in topLevelSections(setOf(Section.Speech)))
    }

    @Test
    fun theOfflineNoteShowsUnderLocalOnlyAndLocalWithRebbleFallback() {
        for (mode in CactusSTTMode.entries) {
            val note = offlineAccuracyNote(mode, serverConfigured = true, rebbleAvailable = true)
            val expected = mode == CactusSTTMode.LocalOnly || mode == CactusSTTMode.RebbleFallback
            assertEquals(expected, note != null, "mode $mode")
        }
    }

    @Test
    fun theOfflineNoteNamesAModeThisPhoneCanPick() {
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.LocalOnly, serverConfigured = true, rebbleAvailable = false)!!
                .contains("Server (with Local Fallback) tries your server first"),
        )
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.LocalOnly, serverConfigured = false, rebbleAvailable = true)!!
                .contains("Rebble (with Local Fallback)"),
        )
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.RebbleFallback, serverConfigured = true, rebbleAvailable = true)!!
                .contains("Rebble (with Local Fallback)"),
        )
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.LocalOnly, serverConfigured = false, rebbleAvailable = false)!!
                .contains("Setting up a Self-hosted Server adds Server (with Local Fallback)"),
        )
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.RebbleFallback, serverConfigured = true, rebbleAvailable = false)!!
                .contains("Server (with Local Fallback) tries your server first"),
        )
        assertTrue(
            offlineAccuracyNote(CactusSTTMode.RebbleFallback, serverConfigured = false, rebbleAvailable = false)!!
                .contains("Setting up a Self-hosted Server"),
        )
    }

    @Test
    fun theMobileDataSwitchShowsInTheServerModesWithWhatHappensOffWifi() {
        for (mode in CactusSTTMode.entries) {
            for (hasModel in listOf(true, false)) {
                val note = serverOnMobileDataNote(mode, hasModel)
                val expected = when {
                    mode == CactusSTTMode.RemoteOnly -> "watch dictation doesn't work and Notebook recordings wait for Wi-Fi."
                    mode != CactusSTTMode.RemoteFirst && mode != CactusSTTMode.LocalFirst -> null
                    !hasModel -> "watch dictation doesn't work until this phone has a speech model, and Notebook recordings wait for Wi-Fi."
                    mode == CactusSTTMode.RemoteFirst ->
                        "watch dictation and Notebook recordings of 30 seconds or less are transcribed on this phone, and longer recordings wait for Wi-Fi."
                    else -> "watch dictation is transcribed on this phone, and Notebook recordings the phone can't transcribe wait for Wi-Fi."
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
