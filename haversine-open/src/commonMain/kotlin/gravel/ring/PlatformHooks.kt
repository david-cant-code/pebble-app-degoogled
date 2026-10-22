package gravel.ring

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import coredevices.haversine.BluetoothFailureReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal expect class PlatformHooks(appContext: Any?) {
    fun startBluetooth(scope: CoroutineScope): StateFlow<Boolean>
    fun peripheral(id: String, advertisement: Advertisement?): Peripheral
    fun mtu(peripheral: Peripheral, scope: CoroutineScope): StateFlow<Int>
    fun onConnected(peripheral: Peripheral)
    fun mapFailure(error: Throwable): BluetoothFailureReason?
    fun gattStatus(error: Throwable): Int?
    fun satelliteId(identifier: Identifier): String
}
