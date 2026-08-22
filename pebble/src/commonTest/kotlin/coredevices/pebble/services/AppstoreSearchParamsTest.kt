package coredevices.pebble.services

import com.algolia.client.api.SearchClient
import com.algolia.client.configuration.ClientOptions
import com.algolia.client.configuration.Host
import com.algolia.client.model.search.TagFilters
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppstoreSearchParamsTest {

    @Test
    fun helperTurnsAnalyticsAndPersonalizationOffAndKeepsTheSearchItself() {
        val filters = TagFilters.of(listOf(TagFilters.of("watchface"), TagFilters.of("android")))
        val params = algoliaSearchParams(query = "cat", tagFilters = filters, page = 2, hitsPerPage = 20)

        assertEquals(false, params.analytics)
        assertEquals(false, params.clickAnalytics)
        assertEquals(false, params.enablePersonalization)
        assertNull(params.userToken)
        assertEquals("cat", params.query)
        assertEquals(filters, params.tagFilters)
        assertEquals(2, params.page)
        assertEquals(20, params.hitsPerPage)
    }

    // Flags on the parameter object only matter if the client serialises
    // them: a client that omitted false values would silently hand the
    // decision back to Algolia's server-side defaults. So this drives the
    // real SearchClient into a mock engine and reads the request body back.
    @Test
    fun searchRequestCarriesTheFlagsOnTheWire() = runTest {
        var body: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond(
                content = """{"hits":[],"nbHits":0,"page":0,"nbPages":0,"hitsPerPage":20,"processingTimeMS":1,"query":"cat","params":"query=cat"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = SearchClient(
            appId = "TESTAPPID",
            apiKey = "search-only-key",
            options = ClientOptions(engine = engine, hosts = listOf(Host("search.invalid"))),
        )

        // The client's own timeouts must run on the real clock: under
        // runTest's virtual clock they fire before the mock engine answers.
        withContext(Dispatchers.Default) {
            client.searchSingleIndex(indexName = "apps", searchParams = algoliaSearchParams(query = "cat"))
        }

        assertEquals("/1/indexes/apps/query", path)
        val json = Json.parseToJsonElement(body ?: error("no request body captured")).jsonObject
        assertEquals(false, json["analytics"]?.jsonPrimitive?.booleanOrNull, "analytics must be sent as false: $body")
        assertEquals(false, json["clickAnalytics"]?.jsonPrimitive?.booleanOrNull, "clickAnalytics must be sent as false: $body")
        assertEquals(false, json["enablePersonalization"]?.jsonPrimitive?.booleanOrNull, "enablePersonalization must be sent as false: $body")
        assertFalse("userToken" in json, "no user token may be attached: $body")
        assertEquals("cat", json["query"]?.jsonPrimitive?.content)
    }
}
