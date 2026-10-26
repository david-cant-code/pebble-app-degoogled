package com.anopticlabs.gravel.firmware

import com.anopticlabs.gravel.network.EndlessResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class PebbleOsChangelogListBoundTest {
    @Test
    fun anEndlessBodyIsUnreadableAndLeavesNoCopierRunning() = runTest {
        val server = EndlessResponse(headersOf(HttpHeaders.ContentType, "application/vnd.github.raw+json")) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // Real time: the body is written on another dispatcher.
        val result = withContext(Dispatchers.Default) { withTimeout(10.seconds) { PebbleOsChangelogListSource(server.client).fetch() } }
        assertIs<ChangelogListResult.Unreadable>(result)
        server.assertNoCopierLeft()
    }
}
