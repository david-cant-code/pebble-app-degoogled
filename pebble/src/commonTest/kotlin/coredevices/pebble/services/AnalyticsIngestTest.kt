package coredevices.pebble.services

import CommonApiConfig
import CoreAppVersion
import coredevices.database.AnalyticsHeartbeatEntity
import coredevices.pebble.Platform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class AnalyticsIngestTest {
    // bugUrl is deliberately non-null: upstream's uploadHeartbeat only skips the
    // POST when bugUrl is null, so this config makes the test fail deterministically
    // if an upstream merge ever restores the upload body.
    private val apiConfigWithBugUrl = object : CommonApiConfig {
        override val version = "1.0.0"
        override val bugUrl = "https://bug.example.invalid"
        override val tokenUrl: String? = null
    }

    @Test
    fun uploadHeartbeatSucceedsWithoutTouchingTheNetworkEvenWithBugUrlConfigured() = runTest {
        val client = HttpClient(MockEngine { request ->
            fail("Telemetry regression: unexpected HTTP request to ${request.url}")
        })
        val ingest = AnalyticsIngest(
            httpClient = client,
            apiConfig = apiConfigWithBugUrl,
            platform = Platform.Android,
            appVersion = CoreAppVersion(version = "1.0.0"),
        )
        val row = AnalyticsHeartbeatEntity(
            serial = "123456789012",
            fwVersion = "v3.8",
            tzOffsetMinutes = 60,
            payload = byteArrayOf(1, 2, 3),
            createdAt = 0L,
        )
        assertTrue(ingest.uploadHeartbeat(row))
    }
}
