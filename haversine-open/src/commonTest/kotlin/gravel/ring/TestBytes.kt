package gravel.ring

import gravel.ring.protocol.MemoryMap
import gravel.ring.protocol.RingChar
import gravel.ring.transport.RawAdvert
import gravel.ring.transport.RingLink
import gravel.ring.transport.RingTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

internal fun u16leBytes(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

internal fun u32leBytes(value: Long): ByteArray = ByteArray(4).also { it.putU32le(0, value) }

internal fun record16(tag: Int, payload: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + u16leBytes(payload.size) + payload

internal fun record32(tag: Int, payload: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + u32leBytes(payload.size.toLong()) + payload

internal fun collectionOf(vararg records: ByteArray): ByteArray {
    var body = ByteArray(0)
    for (record in records) body += record
    return u32leBytes((4 + body.size).toLong()) + body
}

internal fun groupRecord(start: Int, multi: Boolean, final: Boolean): ByteArray = record16(
    0x52,
    u32leBytes(start.toLong()) + byteArrayOf(if (multi) 1 else 0, if (final) 1 else 0),
)

internal fun tapRecord(mask: Long, count: Long): ByteArray =
    record16(0x53, u32leBytes(mask) + u32leBytes(count))

internal fun clockRecords(seconds: Long, offset: Long = 0): ByteArray =
    record16(0x02, u32leBytes(seconds)) + record16(0x35, u32leBytes(offset))

internal fun pcmRecord(rate: Long, vararg samples: Int): ByteArray {
    var payload = u32leBytes(rate)
    for (sample in samples) payload += u16leBytes(sample)
    return record32(0x50, payload)
}

internal fun parseHex(text: String): ByteArray {
    val out = ArrayList<Byte>()
    for (line in text.lineSequence()) {
        val body = line.substringBefore("#").trim()
        if (body.isEmpty()) continue
        for (token in body.split(Regex("\\s+"))) out += token.toInt(16).toByte()
    }
    return out.toByteArray()
}

/**
 * In-memory ring. The server runs inside [RingLink.write] so a test
 * scheduler never has to interleave a second thread.
 */
internal class FakeRing {
    var rangeStart: Int = 0
    var rangeEnd: Int = 0
    val collections = HashMap<Int, ByteArray>()
    val failStatus = HashMap<Int, Int>()
    var needsServicing: Boolean = false
    var recording: Boolean = false
    var recordingReadsUntilClear: Int = 0
    var versions: ByteArray = byteArrayOf(3, 75, 0, 11)
    var hardwareSerial: ByteArray = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
    var programmedSerial: ByteArray = "ABC123".encodeToByteArray()
    var rssi: Byte = (-40).toByte()
    val connectStatuses = ArrayDeque<Int>()
    var connectAttempts: Int = 0
    var clockWrites: Int = 0
    var clockSeconds: Long? = null
    var politeDisconnects: Int = 0
    val reads = mutableListOf<UInt>()
    val ready = MutableStateFlow(true)
    // replay keeps the latest advert for a scan that subscribes afterwards.
    // tryEmit reports success and drops the value when replay is 0 and nobody is collecting.
    private val adverts = MutableSharedFlow<RawAdvert>(replay = 1, extraBufferCapacity = 8)

    fun emit(
        id: String = "RING",
        count: Int = 0,
        servicing: Boolean = false,
        recordingFlag: Boolean = false,
        name: String? = "Pebble Index 001",
    ) {
        val payload = ByteArray(6)
        payload[4] = count.toByte()
        var flags = 0
        if (servicing) flags = flags or (1 shl 6)
        if (recordingFlag) flags = flags or (1 shl 5)
        payload[5] = flags.toByte()
        check(adverts.tryEmit(RawAdvert(id, name, -55, payload, 1_700_000_000_000)))
    }

    fun transport(): RingTransport = object : RingTransport {
        override val bluetoothReady: StateFlow<Boolean> = ready
        override fun advertisements(): Flow<RawAdvert> = adverts
        override suspend fun connect(id: String): RingLink {
            connectAttempts++
            val status = connectStatuses.removeFirstOrNull()
            if (status != null) throw ScriptedConnectException(status)
            return FakeLink(this@FakeRing)
        }
    }

    fun read(address: UInt): Pair<Int, ByteArray> {
        reads += address
        return when (address) {
            MemoryMap.RANGE -> 0 to (u16leBytes(rangeStart) + u16leBytes(rangeEnd))
            MemoryMap.ADVERT -> {
                if (recording && recordingReadsUntilClear > 0) {
                    recordingReadsUntilClear--
                    if (recordingReadsUntilClear == 0) recording = false
                }
                0 to advertImage()
            }
            MemoryMap.VERSIONS -> 0 to versions
            MemoryMap.HARDWARE_SERIAL -> 0 to hardwareSerial
            MemoryMap.PROGRAMMED_SERIAL -> 0 to programmedSerial
            MemoryMap.RX_RSSI -> 0 to byteArrayOf(rssi)
            else -> {
                if (address and 0xFFFF_0000u == MemoryMap.COLLECTION_BASE) {
                    val index = (address and 0xFFFFu).toInt()
                    val failed = failStatus[index]
                    if (failed != null) failed to ByteArray(0) else 0 to (collections[index] ?: ByteArray(0))
                } else {
                    0x43 to ByteArray(0)
                }
            }
        }
    }

    private fun advertImage(): ByteArray {
        var flags = 0
        if (needsServicing) flags = flags or (1 shl 6)
        if (recording) flags = flags or (1 shl 5)
        return byteArrayOf(0x09, 0xFF.toByte(), 0xEA.toByte(), 0x0E, 0, 0, 0, 0, 0, flags.toByte())
    }
}

private class FakeLink(private val ring: FakeRing) : RingLink {
    override val mtu: StateFlow<Int> = MutableStateFlow(177)
    private val controlOut = Channel<ByteArray>(Channel.UNLIMITED)
    private val dataOut = Channel<ByteArray>(Channel.UNLIMITED)
    override val control: Flow<ByteArray> = controlOut.receiveAsFlow()
    override val data: Flow<ByteArray> = dataOut.receiveAsFlow()
    private var controlBuf = ByteSink()
    private var dataBuf = ByteSink()
    private var type: Int = -1
    private var address: UInt = 0u
    private var length: UInt = 0u
    private var haveRequest: Boolean = false

    override suspend fun enableNotifications(ch: RingChar) = Unit

    override suspend fun write(ch: RingChar, bytes: ByteArray, withResponse: Boolean) {
        if (ch == RingChar.Control) {
            controlBuf.append(bytes)
            if (!haveRequest && controlBuf.size >= 13) {
                val raw = controlBuf.snapshot()
                type = raw[0].toInt() and 0xFF
                address = raw.u32le(1).toUInt()
                length = raw.u32le(9).toUInt()
                haveRequest = true
                controlBuf = ByteSink()
            }
        } else if (ch == RingChar.Data) {
            dataBuf.append(bytes)
        }
        if (!haveRequest) return
        if (type == 2 && dataBuf.size < length.toInt()) return
        respond()
        haveRequest = false
        dataBuf = ByteSink()
    }

    override suspend fun close() {
        ring.politeDisconnects++
        controlOut.close()
        dataOut.close()
    }

    private fun respond() {
        var status = 0
        var payload = ByteArray(0)
        when (type) {
            4 -> status = 0x41
            2 -> if (address == MemoryMap.CLOCK && dataBuf.size >= 4) {
                ring.clockWrites++
                ring.clockSeconds = dataBuf.snapshot().u32le(0)
                ring.needsServicing = false
                status = 0x06
            } else {
                status = 0x43
            }
            3 -> {
                val (code, bytes) = ring.read(address)
                status = code
                payload = bytes
            }
            else -> status = 0x43
        }
        if (payload.isNotEmpty()) dataOut.trySend(payload)
        controlOut.trySend(replyBytes(status, if (status == 0 || status == 0x06) payload.size else 0))
    }
}

private fun replyBytes(status: Int, length: Int): ByteArray {
    val out = ByteArray(12)
    out.putU32le(0, status.toLong())
    out.putU32le(8, length.toLong())
    return out
}
