package com.anopticlabs.gravel.firmware

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pins the app's reading of the changelog list (the published file, and one rejected case per
 * rule) and the request that fetches it.
 */
class PebbleOsChangelogListTest {

    /** pebbleos-changelog.json as the firmware-list branch held it on 2026-09-26. */
    private val published = """
{
  "schema": 1,
  "source": "https://ndocs.repebble.com/pebbleos-changelog",
  "checkedAt": "2026-09-26T07:30:38Z",
  "versions": [
    "4.38.1",
    "4.36.0",
    "4.33.2",
    "4.31.2",
    "4.30.0",
    "4.23.0",
    "4.19.2",
    "4.19.1",
    "4.19.0",
    "4.17.0",
    "4.12.0",
    "4.11.0",
    "4.9.183",
    "4.9.178",
    "4.9.175",
    "4.9.171",
    "4.9.163",
    "4.9.157",
    "4.9.153",
    "4.9.152",
    "4.9.135",
    "4.9.127",
    "4.9.125",
    "4.9.121",
    "4.9.111",
    "4.9.108",
    "4.9.100",
    "4.9.91",
    "4.9.88",
    "4.9.83",
    "4.9.76",
    "4.9.63"
  ]
}""".trimStart()

    private fun list(
        schema: String = "1",
        source: String = "\"https://ndocs.repebble.com/pebbleos-changelog\"",
        checkedAt: String = "\"2026-09-26T07:30:38Z\"",
        versions: String = """["4.38.1","4.36.0"]""",
        extra: String = "",
    ) = """{"schema":$schema,"source":$source,"checkedAt":$checkedAt,"versions":$versions$extra}"""

    @Test
    fun acceptsThePublishedList() {
        val parsed = assertNotNull(parsePebbleOsChangelogList(published))
        assertEquals(Instant.parse("2026-09-26T07:30:38Z"), parsed.checkedAt)
        assertEquals(32, parsed.versions.size)
        assertEquals("4.38.1", parsed.versions.first().raw)
        assertEquals("4.9.63", parsed.versions.last().raw)
        assertNotNull(parsePebbleOsChangelogList(list(versions = """["4.999999999.0"]""")))
    }

    @Test
    fun rejectsMalformedLists() {
        val rejected = mapOf(
            "extra key" to list(extra = ""","note":"x""""),
            "missing key" to """{"schema":1,"source":"https://ndocs.repebble.com/pebbleos-changelog","versions":["4.38.1"]}""",
            "schema as string" to list(schema = "\"1\""),
            "schema as float" to list(schema = "1.0"),
            "schema as boolean" to list(schema = "true"),
            "other schema" to list(schema = "2"),
            "other source" to list(source = "\"https://example.com/changelog\""),
            "source as number" to list(source = "1"),
            "fractional seconds" to list(checkedAt = "\"2026-09-26T07:30:38.000Z\""),
            "offset instead of Z" to list(checkedAt = "\"2026-09-26T07:30:38+00:00\""),
            "impossible date" to list(checkedAt = "\"2026-02-30T07:30:38Z\""),
            "year zero" to list(checkedAt = "\"0000-01-01T00:00:00Z\""),
            "checkedAt as number" to list(checkedAt = "1790000000"),
            "version as number" to list(versions = "[4.38]"),
            "leading zero" to list(versions = """["4.038.1"]"""),
            "two components" to list(versions = """["4.38"]"""),
            "four components" to list(versions = """["4.9.142.4"]"""),
            "suffix" to list(versions = """["4.38.1-core59"]"""),
            "v prefix" to list(versions = """["v4.38.1"]"""),
            "ten-digit component" to list(versions = """["4.1000000000.0"]"""),
            "component overflows Int" to list(versions = """["4.99999999999.0"]"""),
            "nested arrays" to list(versions = "[".repeat(20_000) + "]".repeat(20_000)),
            "duplicate" to list(versions = """["4.38.1","4.38.1"]"""),
            "oldest first" to list(versions = """["4.36.0","4.38.1"]"""),
            "empty" to list(versions = "[]"),
            "versions as object" to list(versions = """{"4.38.1":true}"""),
            "top-level array" to """["4.38.1"]""",
            "GitHub contents envelope" to """{"name":"pebbleos-changelog.json","encoding":"base64","content":"e30="}""",
            "not JSON" to "schema: 1",
        )
        for ((case, text) in rejected) {
            assertNull(parsePebbleOsChangelogList(text), "accepted: $case")
        }
    }

    private class FakeList(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val failure: IOException? = null,
        val stream: (() -> ByteReadChannel)? = null,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val source = PebbleOsChangelogListSource(HttpClient(MockEngine { request ->
            requests += request
            failure?.let { throw it }
            val headers = headersOf(HttpHeaders.ContentType, "application/vnd.github.raw+json; charset=utf-8")
            stream?.let { respond(it(), status, headers) } ?: respond(body, status, headers)
        }) {
            // Mirrors the app's shared client, whose content negotiation adds its own Accept value.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
    }

    @Test
    fun fetchesTheRawFileFromTheDataBranch() = runTest {
        val fake = FakeList(published)
        assertIs<ChangelogListResult.Success>(fake.source.fetch())
        val request = fake.requests.single()
        assertEquals("api.github.com", request.url.host)
        assertEquals("/repos/david-cant-code/pebble-app-degoogled/contents/pebbleos-changelog.json", request.url.encodedPath)
        assertEquals(mapOf("ref" to listOf("firmware-list")), request.url.parameters.entries().associate { it.key to it.value })
        val accepts = request.headers.getAll(HttpHeaders.Accept).orEmpty().flatMap { it.split(",") }.map { it.trim() }
        assertTrue("application/vnd.github.raw+json" in accepts, "Accept: $accepts")
        assertEquals("2022-11-28", request.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun refusesABodyOver64KiB() = runTest {
        val limit = 64 * 1024
        val atLimit = published.padEnd(limit)
        assertIs<ChangelogListResult.Success>(FakeList(atLimit).source.fetch())
        assertIs<ChangelogListResult.Unreadable>(FakeList(atLimit + " ").source.fetch())
    }

    @Test
    fun stopsReadingABodyThatNeverEnds() = runTest {
        val endless = FakeList("", stream = {
            CoroutineScope(Dispatchers.Default).writer {
                val spaces = ByteArray(8192) { ' '.code.toByte() }
                channel.writeFully("{".encodeToByteArray())
                repeat(1024) {
                    channel.writeFully(spaces)
                    channel.flush()
                }
                awaitCancellation()
            }.channel
        })
        // Real time: the body is written on another dispatcher.
        val result = withContext(Dispatchers.Default) { withTimeout(10.seconds) { endless.source.fetch() } }
        assertIs<ChangelogListResult.Unreadable>(result)
    }

    @Test
    fun deeplyNestedArraysAreUnreadable() = runTest {
        val nested = list(versions = "[".repeat(20_000) + "]".repeat(20_000))
        assertIs<ChangelogListResult.Unreadable>(FakeList(nested).source.fetch())
    }

    @Test
    fun failuresAreRateLimitedOrUnreadable() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
            assertIs<ChangelogListResult.RateLimited>(FakeList("{}", status).source.fetch(), "$status")
        }
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            assertIs<ChangelogListResult.Unreadable>(FakeList(published, status).source.fetch(), "$status")
        }
        assertIs<ChangelogListResult.Unreadable>(FakeList(list(schema = "2")).source.fetch())
        assertIs<ChangelogListResult.Unreadable>(FakeList("", failure = IOException("network down")).source.fetch())
    }
}
