package com.anopticlabs.gravel.link

import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.ServerConnectionUpdate
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchLinkPaceTest {
    private val watch = "AA:BB:CC:DD:EE:01".asPebbleBleIdentifier()
    private val otherWatch = "AA:BB:CC:DD:EE:02".asPebbleBleIdentifier()

    private fun update(id: PebbleBleIdentifier = watch, latency: Int, succeeded: Boolean = true) =
        ServerConnectionUpdate(deviceId = id, interval = 36, latency = latency, succeeded = succeeded)

    @Test
    fun onlyThisWatchsSucceededReportsAreRead() = runTest {
        val reports = flowOf(
            update(latency = 30),
            null,
            update(id = otherWatch, latency = 0),
            update(latency = 0, succeeded = false),
            update(latency = 0),
        )
        assertEquals(listOf(30, null, 0), reports.reportsFor(watch).toList().map { it?.latency })
    }

    private fun TestScope.link() = WatchLinkPace(watch, ConnectionCoroutineScope(backgroundScope.coroutineContext))

    @Test
    fun thePaceFollowsThisWatchsReports() = runTest {
        val reports = MutableSharedFlow<ServerConnectionUpdate?>()
        val link = link()
        link.attach(reports) { true }
        runCurrent()
        assertEquals(LinkPace.Unknown, link.pace.value)
        reports.emit(update(latency = 30)); runCurrent()
        assertEquals(LinkPace.Slow, link.pace.value)
        reports.emit(update(id = otherWatch, latency = 0)); runCurrent()
        reports.emit(update(latency = 0, succeeded = false)); runCurrent()
        assertEquals(LinkPace.Slow, link.pace.value)
        reports.emit(update(latency = 0)); runCurrent()
        assertEquals(LinkPace.Fast, link.pace.value)
    }

    @Test
    fun aGattServerChangeForgetsThePace() = runTest {
        val reports = MutableSharedFlow<ServerConnectionUpdate?>()
        var requests = 0
        val link = link()
        link.attach(reports) { requests++; true }
        runCurrent()
        reports.emit(update(latency = 30)); runCurrent()
        reports.emit(null); runCurrent()
        assertEquals(LinkPace.Unknown, link.pace.value)
        link.requestFast()
        assertEquals(0, requests)
    }

    @Test
    fun theFastLinkIsAskedForOnASlowLinkAlone() = runTest {
        val reports = MutableSharedFlow<ServerConnectionUpdate?>()
        var requests = 0
        val link = link()
        link.attach(reports) { requests++; true }
        runCurrent()
        link.requestFast()
        assertEquals(0, requests, "a link that has not reported is not asked for")
        reports.emit(update(latency = 30)); runCurrent()
        link.requestFast()
        assertEquals(1, requests)
        reports.emit(update(latency = 0)); runCurrent()
        link.requestFast()
        assertEquals(1, requests, "a fast link is not asked for again")
    }

    @Test
    fun aLinkWithoutPeripheralLatencyIsFast() {
        assertEquals(LinkPace.Fast, update(latency = 0).pace())
    }

    @Test
    fun aLinkWithPeripheralLatencyIsSlow() {
        // The watch's idle latency before PebbleOS v4.39.0 and from it.
        assertEquals(LinkPace.Slow, update(latency = 3).pace())
        assertEquals(LinkPace.Slow, update(latency = 30).pace())
    }
}
