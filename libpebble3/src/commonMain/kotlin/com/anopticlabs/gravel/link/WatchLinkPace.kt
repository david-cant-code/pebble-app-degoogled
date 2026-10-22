package com.anopticlabs.gravel.link

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.ServerConnectionUpdate
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** How promptly the watch hears the phone; [Unknown] until the connection's first link report. */
enum class LinkPace { Unknown, Slow, Fast }

/**
 * One watch connection's link pace, read from the phone stack's link reports, and the phone-side
 * request for the fast link. A connection without reports (BT classic, the emulator, a phone stack
 * that does not deliver them) stays [LinkPace.Unknown].
 */
class WatchLinkPace(
    private val identifier: PebbleIdentifier,
    private val scope: ConnectionCoroutineScope,
) {
    private val logger = Logger.withTag("WatchLinkPace")
    private val _pace = MutableStateFlow(LinkPace.Unknown)
    val pace: StateFlow<LinkPace> = _pace.asStateFlow()

    @Volatile
    private var requestHighPriority: (() -> Boolean)? = null

    /**
     * Called by the BLE transport once its GATT client is connected, with every device's link reports and a
     * null where the GATT server changed, which forgets the pace.
     */
    fun attach(updates: Flow<ServerConnectionUpdate?>, requestHighPriority: () -> Boolean) {
        val bleIdentifier = identifier as? PebbleBleIdentifier ?: return
        this.requestHighPriority = requestHighPriority
        scope.launch {
            updates.reportsFor(bleIdentifier).collect { update ->
                val next = update?.pace() ?: LinkPace.Unknown
                if (next != _pace.value) {
                    val why = update?.let { "interval ${it.interval}, latency ${it.latency}" } ?: "GATT server changed"
                    logger.i { "link pace $next ($why)" }
                }
                _pace.value = next
            }
        }
    }

    /**
     * Asks the phone stack for the fast link when the link reports slow. The watch asks for its idle setting
     * again when the recording ends (PebbleOS v4.41.1, fw/services/voice/voice.c, prv_teardown_session); a link
     * that has not reported may be one the phone manages, which Gravel would leave fast.
     */
    fun requestFast() {
        if (_pace.value != LinkPace.Slow) return
        val requested = requestHighPriority?.invoke() ?: return
        logger.d { "fast link requested from the phone: $requested" }
    }
}

internal fun Flow<ServerConnectionUpdate?>.reportsFor(identifier: PebbleBleIdentifier): Flow<ServerConnectionUpdate?> =
    filter { it == null || (it.succeeded && it.deviceId == identifier) }

/**
 * Fast is a link with no peripheral latency, as in the watch's own fastest state (PebbleOS v4.41.1,
 * fw/comm/ble/gap_le_connect_params.c, s_default_connection_params_table).
 */
internal fun ServerConnectionUpdate.pace(): LinkPace = if (latency == 0) LinkPace.Fast else LinkPace.Slow
