package com.anopticlabs.gravel.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A mock [client] whose response body never ends (after [opening], if given). The first cancel of that body is
 * held for half a second: time for a body copier a reader leaves behind (see readBody) to fill its buffer and fall
 * asleep. Each module whose host tests use it carries a copy of this file; EndlessResponseCopiesTest fails when the
 * copies differ.
 */
internal class EndlessResponse(
    headers: Headers = headersOf(),
    opening: suspend ByteWriteChannel.() -> Unit = {},
    configure: HttpClientConfig<*>.() -> Unit = {},
) {
    private val copiersAtFirstCancel = AtomicInteger(-1)
    private val body = HeldCancelBody(CoroutineScope(Dispatchers.Default).writer {
        channel.opening()
        while (true) channel.writeFully(PIECE)
    }.channel)
    val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headers) }, configure)

    /**
     * Fails unless a job was under [client] at the body's first cancel (the body copier; see readBody) and every job
     * under it ends.
     */
    suspend fun assertNoCopierLeft() = withContext(Dispatchers.Default) {
        assertTrue(copiersAtFirstCancel.get() > 0, "no job was under the client when the body was canceled")
        val ended = withTimeoutOrNull(5.seconds) { client.coroutineContext.job.children.forEach { it.join() } }
        assertNotNull(ended, "a job is still running under the client")
    }

    @OptIn(InternalAPI::class)
    private inner class HeldCancelBody(private val inner: ByteReadChannel) : ByteReadChannel by inner {
        override fun cancel(cause: Throwable?) {
            if (copiersAtFirstCancel.compareAndSet(-1, client.coroutineContext.job.children.count())) Thread.sleep(500)
            inner.cancel(cause)
        }
    }

    private companion object {
        val PIECE = ByteArray(64 * 1024) { ' '.code.toByte() }
    }
}
