package com.anopticlabs.gravel.ring.sync

import com.anopticlabs.gravel.ring.protocol.RingRecording
import com.anopticlabs.gravel.ring.protocol.bitString
import com.anopticlabs.gravel.ring.protocol.bytes
import com.anopticlabs.gravel.ring.protocol.collection
import com.anopticlabs.gravel.ring.protocol.compressedRecord
import com.anopticlabs.gravel.ring.protocol.partRecord
import com.anopticlabs.gravel.ring.protocol.record
import com.anopticlabs.gravel.ring.protocol.u16
import com.anopticlabs.gravel.ring.protocol.u32
import com.anopticlabs.gravel.ring.protocol.u8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Plays the ring's side of a [RingConnection] from the protocol description: answers each request with its
 * bytes on the data channel in MTU-sized notifications and a reply on the control channel. Test-only, written
 * from the same reading of the description as the client.
 */
class FakeRing : RingConnection {
    override val notifications = Channel<RingNotification>(Channel.UNLIMITED)

    var start = 0
    var end = 0
    val collections = mutableMapOf<Int, ByteArray>()
    var recording = false
    var count = 0
    var replyFirst = false
    var refuseStatus: Long? = null
    val refuseCollections = mutableMapOf<Int, Long>()
    var silent = false
    val reads = mutableListOf<Long>()
    var clockSet: Long? = null

    /** Runs before each answer to a range read, to change the ring while a session runs. */
    var beforeRangeRead: () -> Unit = {}

    /** Runs before each answer to an advertisement read. */
    var beforeAdvertisementRead: () -> Unit = {}

    override suspend fun writeControl(bytes: ByteArray) {
        val type = bytes.u8(0)
        val address = bytes.u32(1)
        reads += address
        if (silent) return
        refuseStatus?.let {
            notifications.send(RingNotification.Control(reply(it, 0)))
            return
        }
        if (type == 2) return
        val data = when {
            address == 0x4003_0005L -> {
                beforeRangeRead()
                u16(start) + u16(end)
            }
            address == 0x4003_000EL -> {
                beforeAdvertisementRead()
                bytes(0x09, 0xFF, 0xEA, 0x0E) + u32(0x1234_FFFF) + bytes(count, if (recording) 0x20 else 0)
            }
            address and 0xFFFF_0000L == 0x4002_0000L -> {
                val index = (address and 0xFFFF).toInt()
                refuseCollections[index]?.let {
                    notifications.send(RingNotification.Control(reply(it, 0)))
                    return
                }
                collections.getValue(index)
            }
            else -> error("unexpected read 0x${address.toString(16)}")
        }
        answer(data)
    }

    override suspend fun writeData(bytes: ByteArray) {
        clockSet = bytes.u32(0)
        notifications.send(RingNotification.Control(reply(0, 4)))
    }

    /** Sends bytes the session did not ask for. */
    suspend fun stray() = notifications.send(RingNotification.Data(bytes(1, 2, 3)))

    private suspend fun answer(data: ByteArray) {
        val chunks = data.toList().chunked(174).map { RingNotification.Data(it.toByteArray()) }
        val reply = reply(0, data.size.toLong())
        if (replyFirst) notifications.send(RingNotification.Control(reply))
        chunks.forEach { notifications.send(it) }
        if (!replyFirst) {
            notifications.send(RingNotification.Control(reply.copyOfRange(0, 5)))
            notifications.send(RingNotification.Control(reply.copyOfRange(5, 12)))
        }
    }

    private fun reply(status: Long, length: Long) = u32(status) + u32(0) + u32(length)
}

private val oneSecondOfZeros = bitString("1".repeat(10_000))

/** A collection holding 10,000 silent samples per [seconds] at [rate], part of the recording starting at [startIndex]. */
fun ringPart(startIndex: Int, final: Boolean, seconds: Int = 1, clock: Long = 1_800_000_000, rate: Long = 10_000): ByteArray {
    val (bits, stream) = oneSecondOfZeros
    return collection(
        record(0x35, u32(0xFFFF_FFFF)),
        partRecord(startIndex.toLong(), final),
        compressedRecord(0x84, bits * seconds, rate, ByteArray(stream.size * seconds) { 0xFF.toByte() }),
        record(0x02, u32(clock)),
    )
}

class MemoryStore(var next: Int? = null, var count: Int? = null) : RingSyncStore {
    override suspend fun nextIndex() = next

    override suspend fun setNextIndex(index: Int) {
        // The app's store switches to the I/O dispatcher, which refuses a canceled caller.
        currentCoroutineContext().ensureActive()
        next = index
    }

    override suspend fun lastCount() = count

    override suspend fun setLastCount(count: Int) {
        this.count = count
    }
}

class RecordingSink(var accept: Boolean = true) : RingRecordingSink {
    val stored = mutableListOf<RingRecording>()
    val firstIndexes = mutableListOf<Int>()

    /** Runs after the recording is stored, before the sink answers. */
    var afterStoring: suspend () -> Unit = {}

    override suspend fun store(recording: RingRecording, firstIndex: Int): Boolean {
        if (accept) {
            stored += recording
            firstIndexes += firstIndex
        }
        afterStoring()
        return accept
    }
}
