package io.rebble.libpebblecommon.pebblekit.classic

import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.pebblekit.PebbleKitComponentState
import io.rebble.libpebblecommon.pebblekit.PebbleKitSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells classic PebbleKit clients observing the basalt provider that connection state
 * changed. Fork: only while classic is on, and once when it turns on.
 */
class PebbleKitProviderNotifier(
    private val watchConnected: Flow<Boolean>,
    private val classicEnabled: Flow<Boolean>,
    private val notifyChange: () -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private val logger = Logger.withTag("PebbleKitProviderNotifier")

        fun create(
            watches: Watches,
            libPebbleCoroutineScope: LibPebbleCoroutineScope,
            context: Context,
            componentState: PebbleKitComponentState,
        ) = PebbleKitProviderNotifier(
            watchConnected = watches.watches.map { devices -> devices.any { it is ConnectedPebbleDevice } },
            classicEnabled = classicEnabled(componentState),
            notifyChange = {
                try {
                    context.contentResolver.notifyChange(PebbleKitProvider.URI_CONTENT_BASALT, null)
                } catch (e: SecurityException) {
                    logger.e(e) { "Failed to notify PebbleKitProvider content change - is the provider present in app manifest?" }
                }
            },
            scope = libPebbleCoroutineScope,
        )

        // The provider component as applied: a client re-querying on the toggle-on notification must find it enabled.
        internal fun classicEnabled(componentState: PebbleKitComponentState): Flow<Boolean> =
            componentState.appliedEnabled(PebbleKitSurface.Classic)
    }

    fun init() {
        scope.launch {
            combine(watchConnected, classicEnabled) { connected, enabled -> enabled to connected }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    if (enabled) {
                        notifyChange()
                    }
                }
        }
    }
}
