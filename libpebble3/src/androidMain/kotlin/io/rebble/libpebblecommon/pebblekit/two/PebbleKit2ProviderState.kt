package io.rebble.libpebblecommon.pebblekit.two

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.pebblekit.PebbleKitComponentState
import io.rebble.libpebblecommon.pebblekit.PebbleKitSurface
import io.rebble.libpebblecommon.pebblekit.pebbleKitSurfaceEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger.withTag("PebbleKit2ProviderState")

/**
 * What the `.pebblekit` provider announces: a collection, never one watch. The library announces
 * each watch's active-app URI, built from its identifier, here the real serial (pebblekit2 1.1.0
 * BasePebbleKitProvider.initialize), and the platform checks an observer's access when it
 * registers (android16-release ContentService.registerContentObserver), with no per-observer
 * check when a change is announced (ContentService.ObserverNode.collectMyObserversLocked).
 */
enum class PebbleKit2Change { ConnectedWatches, ActiveApps }

/**
 * Fork: the state behind the `.pebblekit` ContentProvider, owned by the Koin graph so a provider
 * instance created at any time, including after the toggle re-enables it, can serve it. The
 * library keeps this state in the instance, where `query()` waits on a latch only `initialize()`
 * releases (pebblekit2 1.1.0 BasePebbleKitProvider).
 *
 * [connectedWatches] rows carry the real serial under [idColumn]; the provider pseudonymises it.
 */
class PebbleKit2ProviderState(
    private val enabled: Flow<Boolean>,
    private val connectedWatches: Flow<List<Map<String, Any?>>>,
    private val activeAppOf: (serial: String) -> Flow<Map<String, Any?>?>,
    private val idColumn: String,
    private val notify: (PebbleKit2Change) -> Unit,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var watches: List<Map<String, Any?>> = emptyList()
    private val activeApps = ConcurrentHashMap<String, Map<String, Any?>>()

    fun init() {
        scope.launch {
            enabled.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    watches = emptyList()
                    activeApps.clear()
                    logger.d { "PebbleKit 2 is off; not tracking watch state" }
                    return@collectLatest
                }
                track()
            }
        }
    }

    /** The connected-watch rows, real serial included; empty while the toggle is off. */
    fun connectedWatches(): List<Map<String, Any?>> = watches

    fun connectedSerials(): List<String> = watches.mapNotNull { it[idColumn] as? String }

    fun activeApp(serial: String): Map<String, Any?>? = activeApps[serial]?.takeIf { it.isNotEmpty() }

    private suspend fun track() {
        connectedWatches.distinctUntilChanged().collectLatest { list ->
            watches = list
            notify(PebbleKit2Change.ConnectedWatches)
            val serials = list.mapNotNull { it[idColumn] as? String }
            val gone = activeApps.keys.filter { it !in serials }
            val goneWithApp = gone.count { serial -> activeApps.remove(serial).orEmpty().isNotEmpty() }
            if (goneWithApp > 0) notify(PebbleKit2Change.ActiveApps)
            // Children of this per-list scope, so the next list cancels them; on an outer scope
            // they would pile up, one set per list change.
            coroutineScope {
                serials.forEach { serial ->
                    launch {
                        activeAppOf(serial).collect { app ->
                            val now = app.orEmpty()
                            val before = activeApps.put(serial, now).orEmpty()
                            if (before != now) notify(PebbleKit2Change.ActiveApps)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tracking needs both the PebbleKit 2 toggle and its components as applied: the toggle turns on
 * before the components are enabled, and turns off before they are disabled.
 */
internal fun pebbleKit2Tracking(watchConfig: WatchConfigFlow, componentState: PebbleKitComponentState): Flow<Boolean> =
    combine(
        watchConfig.pebbleKitSurfaceEnabled(PebbleKitSurface.PebbleKit2),
        componentState.appliedEnabled(PebbleKitSurface.PebbleKit2),
    ) { on, enabled -> on && enabled }

/** The library's projection rule: null means every column, else the requested columns that exist, in order. */
internal fun projectedColumns(projection: List<String?>?, allColumns: List<String>): List<String> =
    projection?.filterNotNull()?.filter { it in allColumns } ?: allColumns

internal fun rowValues(row: Map<String, Any?>, columns: List<String>): List<Any?> =
    columns.map { row[it] }

/** The rows with the serial under [idColumn] replaced by the caller's pseudonym; a row without one is dropped. */
internal fun pseudonymiseRows(
    rows: List<Map<String, Any?>>,
    idColumn: String,
    pseudonymFor: (serial: String) -> String?,
): List<Map<String, Any?>> = rows.mapNotNull { row ->
    val serial = row[idColumn] as? String ?: return@mapNotNull null
    val pseudonym = pseudonymFor(serial) ?: return@mapNotNull null
    row + (idColumn to pseudonym)
}
