@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, com.juul.kable.ExperimentalApi::class)

package com.anopticlabs.gravel.ring.link

import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import com.anopticlabs.gravel.ring.sync.RingNotification
import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class RingLinkTest {

    private val service = Uuid.parse("0000fcc9-0000-1000-8000-00805f9b34fb")
    private val control = characteristicOf(service, Uuid.parse("c0ef558a-2058-fabf-a140-8d5acde50b39"))
    private val data = characteristicOf(service, Uuid.parse("daad3d52-237c-90a7-b54b-8854a134d801"))
    private val system = characteristicOf(service, Uuid.parse("1d1f4039-23f5-33b2-c24e-704351f20585"))

    /** A connected peripheral whose ring disconnects when system input is subscribed, as the description gives. */
    private class FakePeripheral(override val scope: CoroutineScope) : Peripheral {
        val stateFlow = MutableStateFlow<State>(State.Connected(scope))
        val events = mutableListOf<String>()
        val streams = mutableMapOf<Uuid, Channel<ByteArray>>()
        var confirm: (Uuid) -> Boolean = { true }

        override val state: StateFlow<State> = stateFlow
        override val identifier: Identifier = "00:11:22:33:44:55"
        override val name: String? = null
        override val services: StateFlow<List<DiscoveredService>?> = MutableStateFlow(null)

        override suspend fun connect(): CoroutineScope = scope

        override suspend fun disconnect() {
            stateFlow.value = State.Disconnected()
        }

        override suspend fun maximumWriteValueLengthForType(writeType: WriteType) = 20

        override suspend fun rssi() = 0

        override suspend fun read(characteristic: Characteristic) = ByteArray(0)

        override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
            events += "write"
        }

        override suspend fun read(descriptor: Descriptor) = ByteArray(0)

        override suspend fun write(descriptor: Descriptor, data: ByteArray) = Unit

        override fun observe(characteristic: Characteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> = flow {
            val uuid = characteristic.characteristicUuid
            events += "observe $uuid"
            try {
                if (confirm(uuid)) onSubscription()
                if (uuid == SYSTEM) stateFlow.value = State.Disconnected(State.Disconnected.Status.PeripheralDisconnected)
                emitAll(streams.getOrPut(uuid) { Channel(Channel.UNLIMITED) }.consumeAsFlow())
            } finally {
                events += "end $uuid"
            }
        }

        override fun close() = Unit

        companion object {
            val SYSTEM: Uuid = Uuid.parse("1d1f4039-23f5-33b2-c24e-704351f20585")
        }
    }

    private fun CoroutineScope.peripheral() = FakePeripheral(CoroutineScope(coroutineContext + Job()))

    @Test
    fun aLinkLostWhileSubscribingFailsWithItsStatusInsteadOfWaiting() = runTest {
        val peripheral = peripheral()
        peripheral.confirm = { false }
        launch {
            delay(1.seconds)
            peripheral.stateFlow.value = State.Disconnected(State.Disconnected.Status.Unknown(137))
        }

        val lost = assertFailsWith<RingLinkException> { runRingLink(peripheral, control, data, system, { "unreached" }) }

        assertEquals(137, lost.status)
        assertEquals(RingLinkFailure.PAIR_AGAIN, lost.failure)
        assertEquals(1.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun aLinkLostDuringTheSessionEndsTheWaitForNotifications() = runTest {
        val peripheral = peripheral()
        launch {
            delay(2.seconds)
            peripheral.stateFlow.value = State.Disconnected(State.Disconnected.Status.Unknown(133))
        }

        val lost = assertFailsWith<RingLinkException> {
            runRingLink(peripheral, control, data, system, { it.notifications.receive() })
        }

        assertEquals(RingLinkFailure.RETRY, lost.failure)
        assertEquals(2.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun controlIsSubscribedBeforeDataAndTheHangUpIsSentBeforeEitherEnds() = runTest {
        val peripheral = peripheral()

        assertEquals("done", runRingLink(peripheral, control, data, system, { "done" }))

        val events = peripheral.events.filter { !it.startsWith("write") }
        val c = control.characteristicUuid
        val d = data.characteristicUuid
        assertEquals(listOf("observe $c", "observe $d", "observe ${FakePeripheral.SYSTEM}"), events.take(3))
        assertTrue(events.indexOf("observe ${FakePeripheral.SYSTEM}") < events.indexOf("end $c"))
        assertTrue(events.indexOf("observe ${FakePeripheral.SYSTEM}") < events.indexOf("end $d"))
    }

    @Test
    fun notificationsArriveTaggedWithTheirChannelAndAFloodClosesTheChannel() = runTest {
        val peripheral = peripheral()

        runRingLink(peripheral, control, data, system, { connection ->
            peripheral.streams.getValue(control.characteristicUuid).send(byteArrayOf(1))
            assertIs<RingNotification.Control>(connection.notifications.receive())
            val stream = peripheral.streams.getValue(data.characteristicUuid)
            repeat(300) { stream.send(byteArrayOf(2)) }
            delay(1.seconds)
            repeat(256) { assertIs<RingNotification.Data>(connection.notifications.receive()) }
            assertFailsWith<RingProtocolException> { connection.notifications.receive() }
        })
    }

    @Test
    fun aSessionFailureStillHangsUp() = runTest {
        val peripheral = peripheral()

        assertFailsWith<IllegalStateException> {
            runRingLink(peripheral, control, data, system, { error("session failed") })
        }

        assertTrue("observe ${FakePeripheral.SYSTEM}" in peripheral.events)
    }

    @Test
    fun aSubscriptionTheLinkNeverConfirmsTimesOut() = runTest {
        val peripheral = peripheral()
        peripheral.confirm = { it != data.characteristicUuid }

        assertFailsWith<RingLinkException> { runRingLink(peripheral, control, data, system, { awaitCancellation() }) }
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }
}
