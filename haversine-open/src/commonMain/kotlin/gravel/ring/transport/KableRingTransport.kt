package gravel.ring

import co.touchlab.kermit.Logger
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import gravel.ring.protocol.RingChar
import gravel.ring.transport.RawAdvert
import gravel.ring.transport.RingLink
import gravel.ring.transport.RingTransport
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

internal object RingUuid {
    val scan: Uuid = Uuid.parse("607B5C9B-3700-4E94-F44A-2DF900BCB0C3")
    val service16: Uuid = Uuid.parse("0000FCC9-0000-1000-8000-00805F9B34FB")
    val control: Uuid = Uuid.parse("C0EF558A-2058-FABF-A140-8D5ACDE50B39")
    val data: Uuid = Uuid.parse("DAAD3D52-237C-90A7-B54B-8854A134D801")
    val systemInput: Uuid = Uuid.parse("1D1F4039-23F5-33B2-C24E-704351F20585")
    const val COMPANY = 0x0EEA
}

/**
 * GATT session. Android bonding stays in libindex. On iOS the bond is the
 * write libindex already performs; an insufficient-encryption error is
 * mapped to AuthenticationFailed.
 */
internal class KableRingTransport(
    private val hooks: PlatformHooks,
    private val scope: CoroutineScope,
) : RingTransport {
    private val log = Logger.withTag("GravelRing")
    private val seen = HashMap<String, Advertisement>()
    private val ready by lazy { hooks.startBluetooth(scope) }

    override val bluetoothReady: StateFlow<Boolean>
        get() = ready

    override fun advertisements(): Flow<RawAdvert> = Scanner {
        filters {
            match {
                services = listOf(RingUuid.scan)
            }
        }
    }.advertisements.mapNotNull { advert ->
        val id = hooks.satelliteId(advert.identifier)
        seen[id.lowercase()] = advert
        val payload = advert.manufacturerData
            ?.takeIf { it.code == RingUuid.COMPANY }
            ?.data
            ?: advert.manufacturerData(RingUuid.COMPANY)
        RawAdvert(
            id = id,
            name = advert.name,
            rssi = advert.rssi,
            manufacturerPayload = payload,
            observedAtMillis = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun connect(id: String): RingLink {
        val peripheral = hooks.peripheral(id, seen[id.lowercase()])
        return KableRingLink(peripheral, hooks).open()
    }
}

private class KableRingLink(
    private val peripheral: Peripheral,
    private val hooks: PlatformHooks,
) : RingLink {
    private val controlChannel = Channel<ByteArray>(capacity = 64)
    private val dataChannel = Channel<ByteArray>(capacity = 256)
    private lateinit var connection: CoroutineScope
    private var closed = false

    override val control: Flow<ByteArray> = controlChannel.receiveAsFlow()
    override val data: Flow<ByteArray> = dataChannel.receiveAsFlow()
    override lateinit var mtu: StateFlow<Int>

    suspend fun open(): KableRingLink {
        connection = peripheral.connect()
        hooks.onConnected(peripheral)
        mtu = hooks.mtu(peripheral, connection)
        enableNotifications(RingChar.Control)
        enableNotifications(RingChar.Data)
        return this
    }

    override suspend fun enableNotifications(ch: RingChar) {
        val characteristic = peripheral.find(uuid(ch))
        val target = if (ch == RingChar.Data) dataChannel else controlChannel
        connection.launch {
            peripheral.observe(characteristic).collect { bytes ->
                target.send(bytes)
            }
        }
        // Let the control CCCD land before the data CCCD.
        kotlinx.coroutines.yield()
    }

    override suspend fun write(ch: RingChar, bytes: ByteArray, withResponse: Boolean) {
        peripheral.write(
            peripheral.find(uuid(ch)),
            bytes,
            if (withResponse || ch == RingChar.SystemInput) WriteType.WithResponse else WriteType.WithoutResponse,
        )
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            val system = peripheral.find(RingUuid.systemInput)
            val job = connection.launch { peripheral.observe(system).collect { } }
            val dropped = withTimeoutOrNull(200.milliseconds) {
                peripheral.state.first { it is State.Disconnected }
            }
            job.cancel()
            if (dropped == null) peripheral.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            delay(200.milliseconds)
            runCatching { peripheral.disconnect() }
        }
        runCatching { peripheral.close() }
        controlChannel.close()
        dataChannel.close()
    }

    private fun uuid(ch: RingChar): Uuid = when (ch) {
        RingChar.Control -> RingUuid.control
        RingChar.Data -> RingUuid.data
        RingChar.SystemInput -> RingUuid.systemInput
    }
}

private fun Peripheral.find(uuid: Uuid): com.juul.kable.DiscoveredCharacteristic {
    val discovered = services.value ?: throw NoSuchElementException("GATT services not discovered")
    val preferred = listOf(RingUuid.service16, RingUuid.scan)
    for (serviceId in preferred) {
        val service = discovered.firstOrNull { it.serviceUuid == serviceId } ?: continue
        service.characteristics.firstOrNull { it.characteristicUuid == uuid }?.let { return it }
    }
    return discovered.asSequence()
        .flatMap { it.characteristics.asSequence() }
        .firstOrNull { it.characteristicUuid == uuid }
        ?: throw NoSuchElementException("characteristic $uuid")
}

/** Thrown by the fake ring. Production failures stay platform GATT exceptions. */
internal class ScriptedConnectException(val status: Int) : Exception("GATT $status")
