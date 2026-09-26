package com.anopticlabs.gravel.pkjs

import com.anopticlabs.gravel.socketfilter.InstallResult
import com.anopticlabs.gravel.socketfilter.UnsupportedReason
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CURRENT_WEBVIEW = 153

class UdpFilterEnforcementTest {
    private val dir = createTempDirectory("no_backup").toFile()
    private val declines = listOf(
        null,
        InstallResult.Unsupported(UnsupportedReason.ResolverUnreachable, 111),
        InstallResult.Unsupported(UnsupportedReason.ResolverUncheckable, 110),
    )

    @AfterTest
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun theSwitchAppliesUntilAnInstallIsRecorded() {
        for (result in declines) {
            recordUdpFilterInstall(result, dir)
            val enforcement = udpFilterEnforcement(result, dir) { CURRENT_WEBVIEW }
            assertFalse(enforcement.primaryLayerActive, "$result")
            assertTrue(enforcement.deniedPkjsSwitchApplies, "$result before any install")
        }

        val installed = udpFilterEnforcement(InstallResult.Installed, dir) { CURRENT_WEBVIEW }
        assertTrue(installed.primaryLayerActive)
        assertFalse(installed.deniedPkjsSwitchApplies)
        assertTrue(udpFilterEnforcement(null, dir) { CURRENT_WEBVIEW }.deniedPkjsSwitchApplies, "reading the result recorded an install")

        recordUdpFilterInstall(InstallResult.Installed, dir)
        for (result in declines) {
            val enforcement = udpFilterEnforcement(result, dir) { CURRENT_WEBVIEW }
            assertFalse(enforcement.primaryLayerActive, "$result")
            assertFalse(enforcement.deniedPkjsSwitchApplies, "$result after a recorded install")
        }
    }

    @Test
    fun eachInstallRefreshesTheRecordAndADeclineLeavesIt() {
        recordUdpFilterInstall(InstallResult.Installed, dir)
        val marker = dir.listFiles().orEmpty().single()
        assertTrue(marker.setLastModified(1_000_000L))
        recordUdpFilterInstall(null, dir)
        assertEquals(1_000_000L, marker.lastModified(), "a decline touched the record")
        recordUdpFilterInstall(InstallResult.Installed, dir)
        assertTrue(marker.lastModified() > 1_000_000L, "a second install left the record's time")
    }

    @Test
    fun theSwitchAppliesOnlyWithAWebViewThatCarriesTheHeaderLayer() {
        for ((webViewMajor, applies) in listOf(null to false, 0 to false, 138 to false, 151 to false, 152 to true, 153 to true)) {
            assertEquals(applies, udpFilterEnforcement(null, dir) { webViewMajor }.deniedPkjsSwitchApplies, "WebView $webViewMajor")
        }
        assertTrue(udpFilterEnforcement(InstallResult.Installed, dir) { 138 }.primaryLayerActive)
    }

    // The provider can change while the process runs.
    @Test
    fun theWebViewVersionIsReadAtEachCheck() {
        var current: Int? = 153
        val enforcement = udpFilterEnforcement(null, dir) { current }
        assertTrue(enforcement.deniedPkjsSwitchApplies)
        current = 138
        assertFalse(enforcement.deniedPkjsSwitchApplies)
    }

    @Test
    fun anInstallThatCannotBeRecordedStillReportsTheLayerActive() {
        val missing = File(dir, "absent")
        recordUdpFilterInstall(InstallResult.Installed, missing)
        assertTrue(udpFilterEnforcement(InstallResult.Installed, missing) { CURRENT_WEBVIEW }.primaryLayerActive)
        assertTrue(udpFilterEnforcement(null, missing) { CURRENT_WEBVIEW }.deniedPkjsSwitchApplies)
    }
}
