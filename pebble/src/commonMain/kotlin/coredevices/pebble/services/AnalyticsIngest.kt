package coredevices.pebble.services

import CommonApiConfig
import CoreAppVersion
import coredevices.database.AnalyticsHeartbeatEntity
import coredevices.pebble.Platform
import io.ktor.client.HttpClient

class AnalyticsIngest(
    private val httpClient: HttpClient,
    private val apiConfig: CommonApiConfig,
    private val platform: Platform,
    private val appVersion: CoreAppVersion,
) {
    // This fork never uploads watch diagnostics records (fork goal: strip
    // telemetry). Reporting success makes the queue delete rows enqueued before
    // the fork, so legacy diagnostics data is drained locally, never transmitted.
    suspend fun uploadHeartbeat(row: AnalyticsHeartbeatEntity): Boolean = true
}
