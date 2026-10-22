package com.anopticlabs.gravel.notebook.backup

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupNamesTest {

    @Test
    fun theSuggestedNameIsTheLocalDateAndTimeWithTheBackupExtension() {
        assertEquals("gravel-notebook-2023-11-14-2213.gnbk", suggestedBackupName(1_700_000_000_000L, TimeZone.UTC))
        assertEquals("gravel-notebook-2023-11-15-0713.gnbk", suggestedBackupName(1_700_000_000_000L, TimeZone.of("Asia/Tokyo")))
        assertEquals("gravel-notebook-2026-01-02-0304.gnbk", suggestedBackupName(1_767_323_040_000L, TimeZone.UTC))
    }

    @Test
    fun anAutomaticBackupIsNamedToTheSecond() {
        assertEquals("gravel-notebook-auto-2023-11-14-221320.gnbk", automaticBackupName(1_700_000_000_000L, TimeZone.UTC))
        assertEquals("gravel-notebook-auto-2026-01-02-030405.gnbk", automaticBackupName(1_767_323_045_000L, TimeZone.UTC))
    }
}
