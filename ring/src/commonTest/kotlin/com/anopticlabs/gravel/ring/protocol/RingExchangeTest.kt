package com.anopticlabs.gravel.ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RingExchangeTest {

    private fun reply(status: Long, dataLength: Long) = u32(status) + u32(0) + u32(dataLength)

    @Test
    fun aReadIsDoneOnceItsSplitReplyAndItsDataHaveArrived() {
        val exchange = RingExchange(RingRequest.ReadRange)
        val reply = reply(0, 4)

        assertNull(exchange.onControl(reply.copyOfRange(0, 5)))
        assertNull(exchange.onControl(reply.copyOfRange(5, 12)))
        assertNull(exchange.onData(bytes(1, 0)))
        val done = assertIs<RingOutcome.Done>(exchange.onData(bytes(9, 0)))

        assertContentEquals(bytes(1, 0, 9, 0), done.data)
    }

    @Test
    fun dataMayArriveBeforeTheReply() {
        val exchange = RingExchange(RingRequest.ReadCollection(3))

        assertNull(exchange.onData(ByteArray(100) { 7 }))
        assertNull(exchange.onData(ByteArray(74) { 7 }))
        val done = assertIs<RingOutcome.Done>(exchange.onControl(reply(0, 174)))

        assertEquals(174, done.data.size)
    }

    @Test
    fun bytesBeyondTheReplysDataLengthAreLeftOut() {
        val exchange = RingExchange(RingRequest.ReadCollection(3))

        exchange.onControl(reply(0, 10))
        val done = assertIs<RingOutcome.Done>(exchange.onData(ByteArray(12) { it.toByte() }))

        assertContentEquals(ByteArray(10) { it.toByte() }, done.data)
    }

    @Test
    fun aNonzeroStatusEndsTheExchangeAsRefused() {
        val exchange = RingExchange(RingRequest.ReadCollection(3))

        assertEquals(RingOutcome.Refused(0x41), exchange.onControl(reply(0x41, 0)))
        assertFailsWith<RingProtocolException> { exchange.onData(bytes(1)) }
    }

    @Test
    fun aReplyLongerThanTheRequestMayReturnIsRefused() {
        assertFailsWith<RingProtocolException> { RingExchange(RingRequest.ReadRange).onControl(reply(0, 5)) }
        assertFailsWith<RingProtocolException> {
            RingExchange(RingRequest.ReadCollection(0)).onControl(reply(0, MAX_COLLECTION_SIZE + 1L))
        }
    }

    @Test
    fun dataBeyondWhatTheRequestMayReturnIsRefused() {
        val exchange = RingExchange(RingRequest.ReadAdvertisement)

        exchange.onData(ByteArray(10))
        assertFailsWith<RingProtocolException> { exchange.onData(bytes(1)) }
    }

    @Test
    fun moreControlBytesThanOneReplyAreRefused() {
        assertFailsWith<RingProtocolException> { RingExchange(RingRequest.ReadRange).onControl(ByteArray(13)) }
    }

    @Test
    fun anythingAfterTheOutcomeIsRefused() {
        val exchange = RingExchange(RingRequest.ReadRange)
        exchange.onData(ByteArray(4))
        exchange.onControl(reply(0, 4))

        assertFailsWith<RingProtocolException> { exchange.onControl(bytes(0)) }
        assertFailsWith<RingProtocolException> { exchange.onData(bytes(0)) }
    }

    @Test
    fun aClockWriteIsDoneOnAReplyCountingTheBytesWritten() {
        val written = assertIs<RingOutcome.Done>(RingExchange(RingRequest.SetClock.at(1_800_000_000)).onControl(reply(0, 4)))
        val zero = assertIs<RingOutcome.Done>(RingExchange(RingRequest.SetClock.at(1_800_000_000)).onControl(reply(0, 0)))

        assertEquals(0, written.data.size)
        assertEquals(0, zero.data.size)
    }

    @Test
    fun aClockWriteRefusesAReplyCountingMoreThanItWroteAndAnyData() {
        assertFailsWith<RingProtocolException> { RingExchange(RingRequest.SetClock.at(1)).onControl(reply(0, 5)) }
        assertFailsWith<RingProtocolException> { RingExchange(RingRequest.SetClock.at(1)).onData(bytes(1)) }
    }

    @Test
    fun aFullPageCompletesACollectionRead() {
        val exchange = RingExchange(RingRequest.ReadCollection(0))

        exchange.onControl(reply(0, MAX_COLLECTION_SIZE.toLong()))
        assertNull(exchange.onData(ByteArray(MAX_COLLECTION_SIZE - 1)))
        val done = assertIs<RingOutcome.Done>(exchange.onData(bytes(9)))

        assertEquals(MAX_COLLECTION_SIZE, done.data.size)
        assertEquals(9.toByte(), done.data.last())
    }

    @Test
    fun bytesPastTheDataLengthAreDroppedForEveryRead() {
        val exchange = RingExchange(RingRequest.ReadAdvertisement)

        exchange.onControl(reply(0, 10))
        val done = assertIs<RingOutcome.Done>(exchange.onData(ByteArray(12) { it.toByte() }))

        assertContentEquals(ByteArray(10) { it.toByte() }, done.data)
    }
}
