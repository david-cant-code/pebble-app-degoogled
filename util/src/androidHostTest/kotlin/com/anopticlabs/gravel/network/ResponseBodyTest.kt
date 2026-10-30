package com.anopticlabs.gravel.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ResponseBodyTest {
    @Test
    fun aReadThatStopsEarlyReturns() = runBlocking<Unit>(Dispatchers.Default) {
        val server = EndlessResponse()
        val read = withTimeout(10.seconds) {
            server.client.prepareGet(URL).execute { response -> response.readBody { it.readRemaining(1024).readByteArray().size } }
        }
        assertEquals(1024, read)
        server.assertNoCopierLeft()
    }

    @Test
    fun aReadThatThrowsLeavesNoCopierRunning() = runBlocking<Unit>(Dispatchers.Default) {
        val server = EndlessResponse()
        val thrown = withTimeout(10.seconds) {
            assertFailsWith<IllegalStateException> {
                server.client.prepareGet(URL).execute { response ->
                    response.readBody {
                        it.readRemaining(1024)
                        error("too large")
                    }
                }
            }
        }
        assertEquals("too large", thrown.message)
        server.assertNoCopierLeft()
    }

    @Test
    fun aCanceledReadLeavesNoCopierRunning() = runBlocking<Unit>(Dispatchers.Default) {
        val server = EndlessResponse()
        val reading = CompletableDeferred<Unit>()
        val download = launch {
            server.client.prepareGet(URL).execute { response ->
                response.readBody {
                    it.readRemaining(1024)
                    reading.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        withTimeout(10.seconds) {
            reading.await()
            download.cancelAndJoin()
        }
        server.assertNoCopierLeft()
    }

    @Test
    fun aBodyReadToItsEndArrivesWhole() = runBlocking<Unit>(Dispatchers.Default) {
        val bytes = Random(7).nextBytes(3 * 1024 * 1024)
        val finite = HttpClient(MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.OK) })
        val read = withTimeout(10.seconds) {
            finite.prepareGet(URL).execute { response -> response.readBody { it.readRemaining().readByteArray() } }
        }
        assertContentEquals(bytes, read)
    }

    private companion object {
        const val URL = "https://example.invalid/body"
    }
}
