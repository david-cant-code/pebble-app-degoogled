package com.anopticlabs.gravel.ring.protocol

const val REPLY_SIZE = 12

/** How one request ended: [Done] with the bytes a read returned, or [Refused] with the ring's nonzero status. */
sealed interface RingOutcome {
    class Done(val data: ByteArray) : RingOutcome

    data class Refused(val status: Long) : RingOutcome
}

/**
 * One request's exchange, fed the notifications that arrive while it is outstanding. The reply comes on the
 * control channel, possibly split across notifications. A read's bytes come on the data channel, before or
 * after the reply, and the read is done once the reply is in and at least its data length has arrived; a
 * write is done on its reply, whose data length counts the bytes written. Bytes that do not fit the request
 * throw [RingProtocolException], for example more control bytes than one reply, more data than
 * [RingRequest.maxDataLength], or anything after the outcome.
 *
 * Notifications carry no request identity: after a [RingOutcome.Refused], a thrown exception or an abandoned
 * exchange, bytes still in flight cannot be told from the next request's, so the caller closes the link
 * before sending another request.
 */
class RingExchange(val request: RingRequest) {
    private val reply = ByteArray(REPLY_SIZE)
    private var replyFill = 0
    private var dataLength = -1
    private val data = ByteArray(request.maxDataLength)
    private var dataFill = 0
    private var finished = false

    /** The outcome once [bytes] from the control channel complete the exchange, else null. */
    fun onControl(bytes: ByteArray): RingOutcome? {
        if (finished) throw RingProtocolException("control bytes after the outcome")
        if (replyFill + bytes.size > REPLY_SIZE) throw RingProtocolException("more control bytes than one reply")
        bytes.copyInto(reply, replyFill)
        replyFill += bytes.size
        if (replyFill < REPLY_SIZE) return null
        val status = reply.u32(0)
        if (status != 0L) return RingOutcome.Refused(status).also { finished = true }
        val length = reply.u32(8)
        val bound = if (request.isWrite) request.length else request.maxDataLength.toLong()
        if (length > bound) throw RingProtocolException("a reply of $length bytes to $request")
        if (request.isWrite) {
            finished = true
            return RingOutcome.Done(ByteArray(0))
        }
        dataLength = length.toInt()
        return outcome()
    }

    /**
     * The outcome once [bytes] from the data channel complete the exchange, else null. Bytes past the reply's
     * data length are dropped.
     */
    fun onData(bytes: ByteArray): RingOutcome? {
        if (finished) throw RingProtocolException("data after the outcome")
        val room = (if (dataLength >= 0) dataLength else data.size) - dataFill
        if (dataLength < 0 && bytes.size > room) throw RingProtocolException("more data than $request may return")
        val taken = minOf(bytes.size, room)
        bytes.copyInto(data, dataFill, 0, taken)
        dataFill += taken
        return outcome()
    }

    private fun outcome(): RingOutcome? {
        if (dataLength < 0 || dataFill < dataLength) return null
        finished = true
        return RingOutcome.Done(data.copyOf(dataLength))
    }
}
