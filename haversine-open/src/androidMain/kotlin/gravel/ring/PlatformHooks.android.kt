package gravel.ring

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import coredevices.haversine.BluetoothFailureReason
import gravel.ring.sync.connectFailureReason
import gravel.ring.sync.gattStatusOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

actual class PlatformHooks actual constructor(private val appContext: Any?) {
    actual fun startBluetooth(scope: CoroutineScope): StateFlow<Boolean> {
        val app = (appContext as? Context)?.applicationContext ?: return MutableStateFlow(false)
        val ready = MutableStateFlow(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ready.value = state == BluetoothAdapter.STATE_ON
            }
        }
        try {
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ready.value = adapter?.isEnabled == true
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            scope.coroutineContext[Job]?.invokeOnCompletion {
                runCatching { app.unregisterReceiver(receiver) }
            }
        } catch (_: SecurityException) {
            ready.value = false
        }
        return ready
    }

    actual fun peripheral(id: String, advertisement: Advertisement?): Peripheral {
        if (advertisement != null) return Peripheral(advertisement)
        val hex = id.replace(":", "")
        val mac = hex.chunked(2).joinToString(":") { it.uppercase() }
        return Peripheral(mac)
    }

    /**
     * The ring starts the MTU exchange (177). A second request from the
     * phone can fail the link, so this only observes [AndroidPeripheral.mtu].
     * Until that flow emits, ATT MTU 23 is the chunking baseline.
     */
    actual fun mtu(peripheral: Peripheral, scope: CoroutineScope): StateFlow<Int> {
        val source = (peripheral as AndroidPeripheral).mtu
        val out = MutableStateFlow(source.value ?: 23)
        scope.launch {
            source.collect { negotiated ->
                if (negotiated != null) out.value = negotiated
            }
        }
        return out
    }

    actual fun onConnected(peripheral: Peripheral) {
        (peripheral as? AndroidPeripheral)?.requestConnectionPriority(AndroidPeripheral.Priority.High)
    }

    actual fun mapFailure(error: Throwable): BluetoothFailureReason? = connectFailureReason(error)

    actual fun gattStatus(error: Throwable): Int? = gattStatusOf(error)

    actual fun satelliteId(identifier: Identifier): String = identifier.replace(":", "").uppercase()
}
