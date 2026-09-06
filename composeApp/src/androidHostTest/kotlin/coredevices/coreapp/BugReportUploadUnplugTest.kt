package coredevices.coreapp

import CommonApiConfig
import coredevices.coreapp.di.apiModule
import coredevices.util.CommonBuildKonfig
import coredevices.util.FORK_ISSUE_TRACKER_URL
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two facts the bug report screen's local-export mode rests on:
 * the build carries no bug-reports backend, so the API client's
 * canUseService gate is false and every upload control stays hidden, and
 * the tracker link the export screen shows points at this fork.
 */
class BugReportUploadUnplugTest {

    @Test
    fun buildConfiguresNoBugReportBackend() {
        assertNull(CommonBuildKonfig.BUG_URL)
        val koin = koinApplication { modules(apiModule) }.koin
        assertNull(koin.get<CommonApiConfig>().bugUrl)
    }

    @Test
    fun trackerLinkPointsAtTheFork() {
        assertTrue(FORK_ISSUE_TRACKER_URL.startsWith("https://github.com/"))
        assertTrue(FORK_ISSUE_TRACKER_URL.endsWith("/issues"))
    }
}
