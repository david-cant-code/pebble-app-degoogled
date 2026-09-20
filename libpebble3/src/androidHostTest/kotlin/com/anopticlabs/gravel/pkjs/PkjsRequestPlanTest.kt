package com.anopticlabs.gravel.pkjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PkjsRequestPlanTest {
    private val appJs = "/data/user/0/app/files/pkjs/app.js"

    private fun plan(
        deny: Boolean,
        allowed: Boolean,
        url: Triple<String?, String?, String?>,
        mainFrame: Boolean = false,
    ) = planPkjsRequest(deny, allowed, url.first, url.second, url.third, mainFrame, appJs)

    private val hostPage = Triple<String?, String?, String?>("https", "pkjs.gravel.invalid", "/host.html")
    private val web = Triple<String?, String?, String?>("https", "example.com", "/api")
    private val cleartext = Triple<String?, String?, String?>("http", "example.com", "/api")
    private val appJsFile = Triple<String?, String?, String?>("file", null, appJs)
    private val otherFile = Triple<String?, String?, String?>("file", null, "/data/user/0/app/databases/db")
    private val asset = Triple<String?, String?, String?>("file", null, "/android_asset/startup.js")
    private val nullUrl = Triple<String?, String?, String?>(null, null, null)

    @Test
    fun denySessionServesExactlyTheHostPageToTheMainFrame() {
        assertEquals(PkjsRequestPlan.ServeHostPage, plan(deny = true, allowed = false, hostPage, mainFrame = true))
        val notServed = listOf(
            hostPage to false,
            Triple<String?, String?, String?>("http", "pkjs.gravel.invalid", "/host.html") to true,
            Triple<String?, String?, String?>("https", "pkjs.gravel.invalid", "/other") to true,
            Triple<String?, String?, String?>("https", "pkjs.gravel.invalid", "/host.html/") to true,
            Triple<String?, String?, String?>("https", "sub.pkjs.gravel.invalid", "/host.html") to true,
            web to true,
            appJsFile to true,
        )
        for ((url, mainFrame) in notServed) {
            assertIs<PkjsRequestPlan.Block>(plan(deny = true, allowed = false, url, mainFrame), "$url mainFrame=$mainFrame")
        }
    }

    @Test
    fun nothingPassesThroughWhileTheGrantIsDenied() {
        for (deny in listOf(true, false)) {
            for (url in listOf(web, cleartext, otherFile, asset, nullUrl)) {
                assertIs<PkjsRequestPlan.Block>(plan(deny, allowed = false, url), "deny=$deny $url")
            }
        }
        assertIs<PkjsRequestPlan.Block>(plan(deny = true, allowed = false, appJsFile))
    }

    @Test
    fun denySessionBlocksFileUrlsEvenAfterAGrant() {
        for (url in listOf(appJsFile, otherFile, asset)) {
            assertEquals(PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN), plan(deny = true, allowed = true, url))
        }
        assertEquals(PkjsRequestPlan.PassThrough, plan(deny = true, allowed = true, web))
    }

    @Test
    fun allowedSessionKeepsItsDecisions() {
        assertEquals(PkjsRequestPlan.PassThrough, plan(deny = false, allowed = true, appJsFile))
        assertEquals(PkjsRequestPlan.PassThrough, plan(deny = false, allowed = false, appJsFile))
        assertEquals(PkjsRequestPlan.PassThrough, plan(deny = false, allowed = true, web))
        assertEquals(PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN), plan(deny = false, allowed = true, otherFile))
        assertEquals(PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN), plan(deny = false, allowed = true, nullUrl))
        assertEquals(PkjsRequestPlan.Block(BLOCK_REASON_NETWORK_DENIED), plan(deny = false, allowed = false, web))
        // An allowed session is never served the host page: it goes to the network like any URL.
        assertEquals(PkjsRequestPlan.PassThrough, plan(deny = false, allowed = true, hostPage, mainFrame = true))
    }

    @Test
    fun onlyADenySessionCarriesTheAllowlistHeader() {
        val denyHeaders = pkjsResponseHeaders(denyConstruction = true)
        assertEquals("();webrtc=block", denyHeaders["Connection-Allowlist"])
        assertEquals("no-store", denyHeaders["Cache-Control"])
        assertTrue(pkjsResponseHeaders(denyConstruction = false).isEmpty())
    }
}
