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
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the fork's addition to the cohorts checker: the server's sha-256,
 * which upstream parses and discards, is recorded for the verified installer.
 * A malformed hash records nothing (the installer then refuses that download)
 * but must not break the offer itself.
 */
class CohortsTest {

    private class SignedOutAccounts : PebbleAccountProvider {
        override fun get(): PebbleAccount = object : PebbleAccount {
            override val loggedIn = MutableStateFlow<String?>(null)
            override val devToken = MutableStateFlow<String?>(null)
            override suspend fun setToken(token: String?, bootUrl: String?) {}
            override suspend fun setDevPortalId() {}
        }
    }

    private fun cohortsBody(sha256: String) =
        """{"fw":{"normal":{"friendlyVersion":"v4.4.3-rbl","notes":"legacy notes",""" +
            """"sha-256":"$sha256","timestamp":1762930476,""" +
            """"url":"https://binaries.rebble.io/fw/silk/Pebble-4.4.3-rbl-silk.pbz"}}}"""

    private fun cohorts(body: String, expectations: FirmwareArtifactExpectations): Cohorts {
        val client = HttpClient(MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return Cohorts(
            httpClient = PebbleHttpClient(SignedOutAccounts(), client, lazy { FakeLibPebble() }),
            appVersion = CoreAppVersion("0.0.0"),
            expectations = expectations,
        )
    }

    @Test
    fun recordsServerSha256WhenOfferingAnUpdate() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = cohorts(cohortsBody("B".repeat(64)), expectations)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"))
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals(
            ExpectedFirmwareArtifact(
                sha256Hex = "b".repeat(64),
                sizeBytes = null,
                versionTag = "v4.4.3-rbl",
            ),
            expectations.lookup(update.url),
        )
    }

    @Test
    fun malformedSha256RecordsNothingButStillOffers() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = cohorts(cohortsBody("not-a-hash"), expectations)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"))
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertNull(expectations.lookup(update.url))
    }
}
