package coredevices.pebble.firmware

import CoreAppVersion
import coredevices.pebble.account.PebbleAccount
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.services.PebbleHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FakeLibPebble
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Shared scaffolding for the firmware-update test suite (the WatchInfo
 * fixtures live in TestWatches.kt). Each of these used to be copied per test
 * file; keeping them here means a DTO field or constructor change touches
 * one place.
 */

/** The suite's fixed "now": fixtures state publish ages relative to this. */
val TEST_NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")

val fixedTestClock: Clock = object : Clock {
    override fun now(): Instant = TEST_NOW
}

/** The fork always runs signed out; services still need the provider shape. */
class SignedOutAccounts : PebbleAccountProvider {
    override fun get(): PebbleAccount = object : PebbleAccount {
        override val loggedIn = MutableStateFlow<String?>(null)
        override val devToken = MutableStateFlow<String?>(null)
        override suspend fun setToken(token: String?, bootUrl: String?) {}
        override suspend fun setDevPortalId() {}
    }
}

fun jsonRespondingClient(body: String): HttpClient = HttpClient(MockEngine { _ ->
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

fun testCohorts(client: HttpClient, expectations: FirmwareArtifactExpectations): Cohorts = Cohorts(
    httpClient = PebbleHttpClient(SignedOutAccounts(), client, lazy { FakeLibPebble() }),
    appVersion = CoreAppVersion("0.0.0"),
    expectations = expectations,
)

fun cohortsBody(sha256: String = "b".repeat(64)): String =
    """{"fw":{"normal":{"friendlyVersion":"v4.4.3-rbl","notes":"legacy notes",""" +
        """"sha-256":"$sha256","timestamp":1762930476,""" +
        """"url":"https://binaries.rebble.io/fw/silk/Pebble-4.4.3-rbl-silk.pbz"}}}"""

// GitHub release-list JSON, one builder set for every test faking the
// endpoint: hand-inlined copies drift apart when the DTO changes.

fun assetJson(name: String, digestValue: String?, size: Long): String = buildString {
    append("""{"name":"$name",""")
    append(""""browser_download_url":"https://github.com/coredevices/PebbleOS/releases/download/x/$name",""")
    append(""""digest":${if (digestValue != null) "\"$digestValue\"" else "null"},""")
    append(""""size":$size,"content_type":"application/octet-stream"}""")
}

fun normalAsset(revision: String, tag: String, digestValue: String?, size: Long): String =
    assetJson("normal_${revision}_$tag.pbz", digestValue, size)

fun releaseJson(
    tag: String,
    ageDays: Int,
    assets: List<String>,
    prerelease: Boolean = false,
    draft: Boolean = false,
): String = buildString {
    append("""{"tag_name":"$tag","html_url":"https://github.com/x","prerelease":$prerelease,"draft":$draft,""")
    append(""""published_at":"${TEST_NOW - ageDays.days}","body":"",""")
    append(""""assets":[${assets.joinToString(",")}]}""")
}

fun releaseList(vararg releases: String): String = "[" + releases.joinToString(",") + "]"
