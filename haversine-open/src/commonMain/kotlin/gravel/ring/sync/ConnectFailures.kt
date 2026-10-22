package gravel.ring.sync

import com.juul.kable.GattStatusException
import coredevices.haversine.BluetoothFailureReason
import gravel.ring.ScriptedConnectException

internal fun gattStatusOf(error: Throwable): Int? = when (error) {
    is ScriptedConnectException -> error.status
    is GattStatusException -> error.status
    else -> error.cause?.let { gattStatusOf(it) }
}

internal fun connectFailureReason(error: Throwable): BluetoothFailureReason {
    val status = gattStatusOf(error)
    if (status == 137) return BluetoothFailureReason.PeerRemovedPairingInformation
    if (status == 5 || status == 15) return BluetoothFailureReason.AuthenticationFailed
    val message = error.message.orEmpty()
    if (message.contains("encrypt", ignoreCase = true)) return BluetoothFailureReason.AuthenticationFailed
    return BluetoothFailureReason.ConnectFailed
}

/** GATT 133 and status-less connect errors are retried. 137, 5 and 15 are not. */
internal fun shouldRetryConnect(error: Throwable): Boolean {
    if (connectFailureReason(error) != BluetoothFailureReason.ConnectFailed) return false
    val status = gattStatusOf(error)
    return status == null || status == 133
}
