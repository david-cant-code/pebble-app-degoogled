package gravel.ring

import com.juul.kable.Advertisement
import com.juul.kable.Bluetooth
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import coredevices.haversine.BluetoothFailureReason
import gravel.ring.sync.connectFailureReason
import gravel.ring.sync.gattStatusOf
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

actual class PlatformHooks actual constructor(@Suppress("UNUSED_PARAMETER") appContext: Any?) {
    @Suppress("DEPRECATION")
    actual fun startBluetooth(scope: CoroutineScope): StateFlow<Boolean> =
        Bluetooth.availability
            .map { it is Bluetooth.Availability.Available }
            .stateIn(scope, SharingStarted.Eagerly, false)

    actual fun peripheral(id: String, advertisement: Advertisement?): Peripheral {
        if (advertisement != null) return Peripheral(advertisement)
        return Peripheral(Uuid.parse(id))
    }

    /**
     * Apple negotiates MTU on its own. [Peripheral.maximumWriteValueLengthForType]
     * is already MTU−3, and the protocol subtracts 3 again, so the stored
     * value is that length plus 3. 23 is the value used until the read returns.
     */
    actual fun mtu(peripheral: Peripheral, scope: CoroutineScope): StateFlow<Int> {
        val out = MutableStateFlow(23)
        scope.launch {
            val length = runCatching {
                peripheral.maximumWriteValueLengthForType(WriteType.WithoutResponse)
            }.getOrDefault(20)
            out.value = length + 3
        }
        return out
    }

    actual fun onConnected(peripheral: Peripheral) = Unit

    actual fun mapFailure(error: Throwable): BluetoothFailureReason? = connectFailureReason(error)

    actual fun gattStatus(error: Throwable): Int? = gattStatusOf(error)

    actual fun satelliteId(identifier: Identifier): String = identifier.toString()
}
