package com.anopticlabs.gravel.pkjs

import androidx.test.filters.SmallTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SmallTest
class PkjsRequestPlanResponseTest {
    private val denyHeaders = pkjsResponseHeaders(denyConstruction = true)

    @Test
    fun hostPageResponseKeepsStatusHeadersAndBody() {
        val response = PkjsRequestPlan.ServeHostPage
            .toWebResourceResponse(denyConstruction = true) { "page".byteInputStream() }!!
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        assertEquals(denyHeaders, response.responseHeaders)
        assertEquals("page", response.data.bufferedReader().readText())
    }

    @Test
    fun blockResponseKeepsStatusReasonAndHeaders() {
        val response = PkjsRequestPlan.Block(BLOCK_REASON_NETWORK_DENIED)
            .toWebResourceResponse(denyConstruction = true) { error("a block opens no page") }!!
        assertEquals(403, response.statusCode)
        assertEquals(BLOCK_REASON_NETWORK_DENIED, response.reasonPhrase)
        assertEquals(denyHeaders, response.responseHeaders)
    }

    @Test
    fun allowedSessionBlockCarriesNoHeaders() {
        val response = PkjsRequestPlan.Block(BLOCK_REASON_FORBIDDEN)
            .toWebResourceResponse(denyConstruction = false) { error("a block opens no page") }!!
        assertEquals(403, response.statusCode)
        assertTrue(response.responseHeaders.isEmpty())
    }

    @Test
    fun passThroughHasNoResponse() {
        assertNull(PkjsRequestPlan.PassThrough.toWebResourceResponse(denyConstruction = true) { error("not opened") })
    }
}
