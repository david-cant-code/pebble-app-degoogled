package gravel.ring.protocol

import gravel.ring.ByteSink
import gravel.ring.putU32le
import gravel.ring.transport.chunk
import gravel.ring.transport.RingLink
import gravel.ring.u32le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest

class RingProtocolTest {
    @Test
    fun requestIsThirteenLittleEndianBytes() {
        val bytes = RingRequest.Read(0x4003_0005u, 4u, 0u).encode()
        assertEquals(13, bytes.size)
        assertEquals(3, bytes[0].toInt())
        assertEquals(0x4003_0005u, bytes.u32le(1).toUInt())
        assertEquals(4u, bytes.u32le(9).toUInt())
    }

    @Test
    fun replyDecodesTwelveBytes() {
        val raw = ByteArray(12).also {
            it.putU32le(0, 0x42)
            it.putU32le(4, 7)
            it.putU32le(8, 3)
        }
        val reply = RingReply.decode(raw)
        assertEquals(0x42u, reply.status)
        assertEquals(7u, reply.reserved)
        assertEquals(3u, reply.dataLength)
    }

    @Test
    fun allowListRejectsFailsafeAndUncapturedEraseBeforeAnyWrite() = runTest {
        val link = ScriptedLink(mtu = 23)
        val protocol = RingProtocol(link)
        assertFailsWith<AddressNotAllowed> {
            protocol.read(RingRequest.Read(MemoryMap.FAILSAFE_IMAGE, 0u))
        }
        assertFailsWith<AddressNotAllowed> {
            protocol.erase(RingRequest.Erase(MemoryMap.ERASE_ALL, 2u))
        }
        assertFailsWith<AddressNotAllowed> {
            protocol.write(RingRequest.Write(0x4000_0000u, byteArrayOf(1)))
        }
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun clockWriteIsAllowedAndEraseCanBeEnabledForATest() {
        assertTrue(addressAllowed(2, MemoryMap.CLOCK))
        assertTrue(!addressAllowed(1, MemoryMap.ERASE_ALL))
        RingFeatureFlags.allowUncapturedErase = true
        try {
            assertTrue(addressAllowed(1, MemoryMap.ERASE_ALL))
        } finally {
            RingFeatureFlags.allowUncapturedErase = false
        }
        assertTrue(!addressAllowed(1, MemoryMap.FAILSAFE_IMAGE))
        assertTrue(addressAllowed(4, MemoryMap.CLOCK))
        assertTrue(!addressAllowed(4, MemoryMap.FAILSAFE_IMAGE))
    }

    @Test
    fun dataMayArriveBeforeASplitReply() = runTest {
        val link = ScriptedLink(mtu = 177)
        link.onRequest = { request, payload ->
            assertEquals(3, request.type)
            assertEquals(0, payload.size)
            assertEquals(4u, request.length)
            link.sendData(byteArrayOf(1, 2, 3, 4))
            val reply = reply(0, 4)
            link.sendControl(reply.copyOfRange(0, 6))
            link.sendControl(reply.copyOfRange(6, 12))
        }
        val bytes = RingProtocol(link).read(RingRequest.Read(MemoryMap.RANGE, 4u))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
    }

    @Test
    fun status42IsRetriedOnce() = runTest {
        val link = ScriptedLink(mtu = 177)
        var seen = 0
        link.onRequest = { _, _ ->
            seen++
            link.sendControl(reply(if (seen == 1) 0x42 else 0, 0))
        }
        RingProtocol(link).read(RingRequest.Read(MemoryMap.RANGE, 4u))
        assertEquals(2, seen)
    }

    @Test
    fun timeoutSendsCancelAndFails() = runTest {
        val link = ScriptedLink(mtu = 177)
        link.onRequest = { request, _ ->
            if (request.type == 4) link.sendControl(reply(0x41, 0))
        }
        assertFailsWith<RingRequestTimeout> {
            RingProtocol(link).read(RingRequest.Read(MemoryMap.RANGE, 4u))
        }
        assertTrue(link.writes.any { it.first == RingChar.Control && it.second[0].toInt() == 4 })
    }

    @Test
    fun chunksFollowMtuMinusThree() = runTest {
        val small = ScriptedLink(mtu = 23)
        small.onRequest = { request, payload ->
            if (request.type == 2 && payload.size >= request.length.toInt()) {
                small.sendControl(reply(0x06, 0))
            }
        }
        val payload = ByteArray(100) { 7 }
        RingProtocol(small).write(RingRequest.Write(MemoryMap.CLOCK, payload))
        val dataChunks = small.writes.filter { it.first == RingChar.Data }.map { it.second.size }
        assertEquals(listOf(20, 20, 20, 20, 20), dataChunks)

        val large = ScriptedLink(mtu = 177)
        large.onRequest = { request, body ->
            if (request.type == 2 && body.size >= request.length.toInt()) {
                large.sendControl(reply(0x06, 0))
            }
        }
        RingProtocol(large).write(RingRequest.Write(MemoryMap.CLOCK, ByteArray(200)))
        assertEquals(
            listOf(174, 26),
            large.writes.filter { it.first == RingChar.Data }.map { it.second.size },
        )
        assertEquals(listOf(20, 20), chunk(ByteArray(40), 20).map { it.size })
    }
}

private fun reply(status: Int, length: Int): ByteArray = ByteArray(12).also {
    it.putU32le(0, status.toLong())
    it.putU32le(8, length.toLong())
}

private class ScriptedRequest(
    val type: Int,
    val address: UInt,
    val length: UInt,
)

private class ScriptedLink(mtu: Int) : RingLink {
    override val mtu: StateFlow<Int> = MutableStateFlow(mtu)
    private val controlOut = Channel<ByteArray>(Channel.UNLIMITED)
    private val dataOut = Channel<ByteArray>(Channel.UNLIMITED)
    override val control: Flow<ByteArray> = controlOut.receiveAsFlow()
    override val data: Flow<ByteArray> = dataOut.receiveAsFlow()
    val writes = mutableListOf<Pair<RingChar, ByteArray>>()
    var onRequest: (suspend (ScriptedRequest, ByteArray) -> Unit)? = null
    private var controlBuf = ByteSink()
    private var dataBuf = ByteSink()
    private var pending: ScriptedRequest? = null

    override suspend fun enableNotifications(ch: RingChar) = Unit

    override suspend fun write(ch: RingChar, bytes: ByteArray, withResponse: Boolean) {
        writes += ch to bytes.copyOf()
        if (ch == RingChar.Control) {
            controlBuf.append(bytes)
            if (pending == null && controlBuf.size >= 13) {
                val raw = controlBuf.snapshot()
                pending = ScriptedRequest(
                    raw[0].toInt() and 0xFF,
                    raw.u32le(1).toUInt(),
                    raw.u32le(9).toUInt(),
                )
                controlBuf = ByteSink()
            }
        } else if (ch == RingChar.Data) {
            dataBuf.append(bytes)
        }
        val request = pending ?: return
        if (request.type == 2 && dataBuf.size < request.length.toInt()) return
        val payload = dataBuf.snapshot()
        pending = null
        dataBuf = ByteSink()
        onRequest?.invoke(request, payload)
    }

    override suspend fun close() {
        controlOut.close()
        dataOut.close()
    }

    fun sendControl(bytes: ByteArray) {
        controlOut.trySend(bytes)
    }

    fun sendData(bytes: ByteArray) {
        dataOut.trySend(bytes)
    }
}
