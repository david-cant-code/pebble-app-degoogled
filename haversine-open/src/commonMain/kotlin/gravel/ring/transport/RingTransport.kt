package gravel.ring.transport

import gravel.ring.protocol.RingChar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class RawAdvert(
    val id: String,
    val name: String?,
    val rssi: Int,
    val manufacturerPayload: ByteArray?,
    val observedAtMillis: Long,
)

internal interface RingLink {
    val mtu: StateFlow<Int>
    val control: Flow<ByteArray>
    val data: Flow<ByteArray>
    suspend fun enableNotifications(ch: RingChar)
    suspend fun write(ch: RingChar, bytes: ByteArray, withResponse: Boolean)
    suspend fun close()
}

internal interface RingTransport {
    val bluetoothReady: StateFlow<Boolean>
    fun advertisements(): Flow<RawAdvert>
    suspend fun connect(id: String): RingLink
}

internal object DeadTransport : RingTransport {
    override val bluetoothReady: StateFlow<Boolean> = MutableStateFlow(true)
    override fun advertisements(): Flow<RawAdvert> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun connect(id: String): RingLink {
        throw IllegalStateException("KMPHaversineSatelliteManager was built without its platform constructor")
    }
}

/** Splits an ATT payload into MTU-3 byte chunks. */
internal fun chunk(bytes: ByteArray, maxChunk: Int): List<ByteArray> {
    val size = maxChunk.coerceAtLeast(1)
    if (bytes.isEmpty()) return emptyList()
    val out = ArrayList<ByteArray>((bytes.size + size - 1) / size)
    var at = 0
    while (at < bytes.size) {
        val end = (at + size).coerceAtMost(bytes.size)
        out += bytes.copyOfRange(at, end)
        at = end
    }
    return out
}
