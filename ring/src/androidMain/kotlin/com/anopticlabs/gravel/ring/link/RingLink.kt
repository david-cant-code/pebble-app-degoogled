package com.anopticlabs.gravel.ring.link

import android.bluetooth.BluetoothDevice
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.sync.RingConnection
import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import com.anopticlabs.gravel.ring.sync.RingNotification
import com.anopticlabs.gravel.ring.sync.ringLinkFailure
import com.juul.kable.Characteristic
import com.juul.kable.GattStatusException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.logs.Logging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The service UUID the ring advertises, for scan filters. */
val RING_ADVERTISED_SERVICE: Uuid = Uuid.parse("607b5c9b-3700-4e94-f44a-2df900bcb0c3")

private val CONTROL = Uuid.parse("c0ef558a-2058-fabf-a140-8d5acde50b39")
private val DATA = Uuid.parse("daad3d52-237c-90a7-b54b-8854a134d801")
private val SYSTEM_INPUT = Uuid.parse("1d1f4039-23f5-33b2-c24e-704351f20585")

/** Notifications a session may leave unread before the link counts as flooded. */
private const val NOTIFICATION_BUFFER = 256

private val log = Logger.withTag("RingLink")

/** The link to the ring failed. [status] is the status Android reported, when it reported one. */
class RingLinkException(
    val status: Int?,
    cause: Throwable?,
    val failure: RingLinkFailure = ringLinkFailure(status),
) : Exception("ring link failed: $failure, status $status", cause)

/**
 * Connects to the bonded ring [device], runs [block] over the connection (see [runRingLink]), then disconnects
 * and closes the link. A device that is not bonded is refused before connecting, as needing pairing again.
 */
suspend fun <T> withRingConnection(device: BluetoothDevice, block: suspend (RingConnection) -> T): T {
    // Kable's Data level logs I/O bytes (Kable 0.43.1, Logging.Level.Data), which here are recorded audio.
    val peripheral = Peripheral(device) { logging { level = Logging.Level.Warnings } }
    try {
        val (control, data, system) = linkStep(peripheral) {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw RingLinkException(null, null, RingLinkFailure.PAIR_AGAIN)
            }
            peripheral.connect()
            val found = peripheral.services.value.orEmpty().flatMap { it.characteristics }
            if (found.isEmpty()) throw RingLinkException(null, null)
            listOf(CONTROL, DATA, SYSTEM_INPUT).map { uuid ->
                found.firstOrNull { it.characteristicUuid == uuid } ?: throw RingProtocolException("the ring has no characteristic $uuid")
            }
        }
        return runRingLink(peripheral, control, data, system, block)
    } finally {
        withContext(NonCancellable) {
            try {
                peripheral.disconnect()
            } catch (e: Exception) {
                log.w(e) { "disconnect failed" }
            }
        }
        peripheral.close()
    }
}

/**
 * Over a connected [peripheral]: subscribes [control] and then [data], runs [block], and hangs up by
 * subscribing to [system], the ring's disconnect signal, before the other subscriptions end. The link going
 * down at any point, or a subscription not confirmed within [subscribeTimeout], throws [RingLinkException];
 * [block]'s own exceptions pass through.
 */
internal suspend fun <T> runRingLink(
    peripheral: Peripheral,
    control: Characteristic,
    data: Characteristic,
    system: Characteristic,
    block: suspend (RingConnection) -> T,
    subscribeTimeout: Duration = 10.seconds,
): T = coroutineScope {
    val notifications = Channel<RingNotification>(NOTIFICATION_BUFFER)
    // An observation stays open across a lost link (Kable 0.43.1, Peripheral.observe), so the state is watched.
    val watcher = launch {
        val gone = peripheral.state.first { it is State.Disconnected } as State.Disconnected
        val failure = RingLinkException((gone.status as? State.Disconnected.Status.Unknown)?.status, null)
        notifications.close(failure)
        throw failure
    }
    val controlJob = subscribe(peripheral, control, notifications, RingNotification::Control, subscribeTimeout)
    val dataJob = subscribe(peripheral, data, notifications, RingNotification::Data, subscribeTimeout)
    try {
        block(KableRingConnection(peripheral, control, data, notifications))
    } finally {
        watcher.cancel()
        withContext(NonCancellable) { hangUp(peripheral, system) }
        controlJob.cancel()
        dataJob.cancel()
    }
}

private class KableRingConnection(
    private val peripheral: Peripheral,
    private val control: Characteristic,
    private val data: Characteristic,
    override val notifications: ReceiveChannel<RingNotification>,
) : RingConnection {
    override suspend fun writeControl(bytes: ByteArray) =
        linkStep(peripheral) { peripheral.write(control, bytes, WriteType.WithoutResponse) }

    override suspend fun writeData(bytes: ByteArray) =
        linkStep(peripheral) { peripheral.write(data, bytes, WriteType.WithoutResponse) }
}

private suspend fun CoroutineScope.subscribe(
    peripheral: Peripheral,
    characteristic: Characteristic,
    sink: Channel<RingNotification>,
    wrap: (ByteArray) -> RingNotification,
    timeout: Duration,
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch {
        linkStep(peripheral) {
            peripheral.observe(characteristic) { subscribed.complete(Unit) }.collect { bytes ->
                if (sink.trySend(wrap(bytes)).isFailure) sink.close(RingProtocolException("more notifications than the session read"))
            }
        }
    }
    if (withTimeoutOrNull(timeout) { subscribed.await() } == null) {
        job.cancel()
        throw RingLinkException(null, null)
    }
    return job
}

/** When the hang-up signal cannot be sent, the caller's disconnect follows a short wait. */
private suspend fun hangUp(peripheral: Peripheral, system: Characteristic) {
    try {
        withTimeoutOrNull(1.seconds) {
            coroutineScope {
                val subscribing = launch { peripheral.observe(system).collect {} }
                peripheral.state.first { it is State.Disconnected }
                subscribing.cancel()
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.w(e) { "hang-up signal failed" }
        delay(200.milliseconds)
    }
}

/** Runs [step], turning a failure of the link into [RingLinkException] with the status Android reported. */
private suspend fun <T> linkStep(peripheral: Peripheral, step: suspend () -> T): T = try {
    step()
} catch (e: CancellationException) {
    throw e
} catch (e: RingProtocolException) {
    throw e
} catch (e: RingLinkException) {
    throw e
} catch (e: Exception) {
    val status = (e as? GattStatusException)?.status
        ?: ((peripheral.state.value as? State.Disconnected)?.status as? State.Disconnected.Status.Unknown)?.status
    throw RingLinkException(status, e)
}
