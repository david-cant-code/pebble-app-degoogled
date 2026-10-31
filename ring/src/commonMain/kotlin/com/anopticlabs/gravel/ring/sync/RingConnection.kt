package com.anopticlabs.gravel.ring.sync

import kotlinx.coroutines.channels.ReceiveChannel

/** A notification from the ring, tagged with the channel it arrived on. */
sealed interface RingNotification {
    class Control(val bytes: ByteArray) : RingNotification

    class Data(val bytes: ByteArray) : RingNotification
}

/**
 * A connected ring whose control and data channels are both subscribed. [notifications] carries both
 * channels, each in its own order (an exchange accepts a read's data before or after its reply); writes go
 * out without response.
 */
interface RingConnection {
    val notifications: ReceiveChannel<RingNotification>

    suspend fun writeControl(bytes: ByteArray)

    suspend fun writeData(bytes: ByteArray)
}
