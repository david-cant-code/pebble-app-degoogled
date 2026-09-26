package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * libpebble3's `updateFirmware` is the unverified install path that VerifiedFirmwareInstaller
 * replaces (see its KDoc); no source outside libpebble3 may call or reference it. Test and iOS
 * sources are not scanned.
 */
class UnverifiedFirmwareInstallTest {
    private val unverifiedInstall = Regex("""\bupdateFirmware\b""")
    private val notScanned = Regex("""/src/(ios[^/]*|[^/]*[Tt]est[^/]*)/""")

    @Test
    fun noSourceOutsideLibpebble3ReachesTheUnverifiedInstall() {
        val sources = TrackedTree.files.filter { path ->
            path.endsWith(".kt") && !path.startsWith("libpebble3/") && !notScanned.containsMatchIn(path)
        }
        assertTrue(sources.any { it.endsWith("/firmware/VerifiedFirmwareInstaller.kt") }, "the scan found no app sources")
        val callers = sources.filter { unverifiedInstall.containsMatchIn(TrackedTree.file(it).readText()) }
        assertEquals(emptyList(), callers)
    }
}
