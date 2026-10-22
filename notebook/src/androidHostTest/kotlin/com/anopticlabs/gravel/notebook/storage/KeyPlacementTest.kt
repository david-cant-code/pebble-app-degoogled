package com.anopticlabs.gravel.notebook.storage

import android.os.Build
import android.security.keystore.KeyProperties
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyPlacementTest {
    private fun on(sdk: Int, level: Int = -99, inside: Boolean = false) = NotebookKeystore.placementOf(sdk, { level }, { inside })

    @Test
    fun fromApi31TheSecurityLevelNamesThePlace() {
        assertEquals(KeyPlacement.STRONGBOX, on(Build.VERSION_CODES.S, KeyProperties.SECURITY_LEVEL_STRONGBOX))
        assertEquals(KeyPlacement.TEE, on(36, KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, inside = true))
        assertEquals(KeyPlacement.SOFTWARE, on(36, KeyProperties.SECURITY_LEVEL_SOFTWARE, inside = true))
        assertEquals(KeyPlacement.SECURE_HARDWARE, on(36, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE, inside = true))
        assertEquals(KeyPlacement.UNKNOWN, on(36, KeyProperties.SECURITY_LEVEL_UNKNOWN, inside = true))
    }

    @Test
    fun beforeApi31OnlySecureHardwareOrSoftwareCanBeTold() {
        assertEquals(KeyPlacement.SECURE_HARDWARE, on(Build.VERSION_CODES.R, KeyProperties.SECURITY_LEVEL_STRONGBOX, inside = true))
        assertEquals(KeyPlacement.SOFTWARE, on(Build.VERSION_CODES.P, inside = false))
    }
}
