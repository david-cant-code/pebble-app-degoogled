package gravel.ring.protocol

import co.touchlab.kermit.Logger
import gravel.ring.ByteSink
import gravel.ring.transport.RingLink
import gravel.ring.transport.chunk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * One request at a time. ASSUMPTION: a read of up to 4 KiB completes within
 * 5 s, and a write's reply arrives after the data bytes.
 */
internal class RingProtocol(private val link: RingLink) {
    private val lock = Mutex()
    private val log = Logger.withTag("GravelRing")

    suspend fun read(request: RingRequest.Read, timeout: Duration = 5.seconds): ByteArray =
        exchange(request, timeout) { reply, data ->
            if (data.size < reply.dataLength.toInt()) {
                throw RingStatusException(reply.status, request.address)
            }
            data.copyOf(reply.dataLength.toInt())
        }

    suspend fun write(request: RingRequest.Write, timeout: Duration = 5.seconds) {
        exchange(request, timeout) { _, _ -> ByteArray(0) }
    }

    suspend fun erase(request: RingRequest.Erase, timeout: Duration = 5.seconds) {
        exchange(request, timeout) { _, _ -> ByteArray(0) }
    }

    private suspend fun <T> exchange(
        request: RingRequest,
        timeout: Duration,
        accept: (RingReply, ByteArray) -> T,
    ): T {
        if (!request.allowed()) throw AddressNotAllowed(request.address, request.type)
        return lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    return@withLock withTimeout(timeout) { roundTrip(request, accept) }
                } catch (e: TimeoutCancellationException) {
                    runCatching { cancel(request) }
                    throw RingRequestTimeout(request.address, e)
                } catch (e: RingCancelledByWrite) {
                    if (attempt >= 1) throw e
                    attempt++
                    log.i { "status 0x42 at 0x${request.address.toString(16)}, retrying once" }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("exchange")
        }
    }

    private suspend fun <T> roundTrip(
        request: RingRequest,
        accept: (RingReply, ByteArray) -> T,
    ): T {
        val incoming = Channel<Piece>(Channel.UNLIMITED)
        val controlJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
            .launch(Dispatchers.Unconfined) {
                link.control.collect { incoming.send(Piece.Control(it)) }
            }
        val dataJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
            .launch(Dispatchers.Unconfined) {
                link.data.collect { incoming.send(Piece.Data(it)) }
            }
        try {
            writeChunks(RingChar.Control, request.encode(), withResponse = false)
            if (request is RingRequest.Write) {
                writeChunks(RingChar.Data, request.payload, withResponse = false)
            }
            val replyBytes = ByteSink()
            val dataBytes = ByteSink()
            var reply: RingReply? = null
            while (true) {
                val got = reply
                if (got != null && dataBytes.size >= got.dataLength.toInt()) break
                when (val piece = incoming.receive()) {
                    is Piece.Control -> replyBytes.append(piece.bytes)
                    is Piece.Data -> dataBytes.append(piece.bytes)
                }
                if (reply == null && replyBytes.size >= RingReply.SIZE) {
                    val decoded = RingReply.decode(replyBytes.snapshot())
                    if (decoded.reserved != 0u) {
                        log.i { "non-zero reserved word 0x${decoded.reserved.toString(16)}" }
                    }
                    if (decoded.dataLength > MemoryMap.MAX_REPLY_BYTES.toUInt()) {
                        throw RingStatusException(decoded.status, request.address)
                    }
                    reply = decoded
                }
            }
            interpret(request, reply)
            return accept(reply, dataBytes.snapshot())
        } finally {
            controlJob.cancel()
            dataJob.cancel()
            incoming.close()
        }
    }

    private fun interpret(request: RingRequest, reply: RingReply) {
        val status = reply.status
        log.d { "reply 0x${status.toString(16)} len ${reply.dataLength} at 0x${request.address.toString(16)}" }
        when (status) {
            0x00u -> return
            0x06u -> if (request is RingRequest.Write) return
            0x41u -> if (request is RingRequest.Cancel) return
            0x42u -> throw RingCancelledByWrite(request.address)
            0x04u, 0x05u -> throw RingMissingDataException(status, request.address)
        }
        throw RingStatusException(status, request.address)
    }

    private suspend fun cancel(request: RingRequest) {
        val cancel = RingRequest.Cancel(request)
        if (!cancel.allowed()) return
        try {
            withTimeout(1.seconds) {
                roundTrip(cancel) { _, _ -> ByteArray(0) }
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) return
            throw e
        } catch (_: RingStatusException) {
        }
    }

    private suspend fun writeChunks(ch: RingChar, bytes: ByteArray, withResponse: Boolean) {
        val max = (link.mtu.value - 3).coerceAtLeast(1)
        for (part in chunk(bytes, max)) link.write(ch, part, withResponse)
    }

    private sealed interface Piece {
        class Control(val bytes: ByteArray) : Piece
        class Data(val bytes: ByteArray) : Piece
    }
}
