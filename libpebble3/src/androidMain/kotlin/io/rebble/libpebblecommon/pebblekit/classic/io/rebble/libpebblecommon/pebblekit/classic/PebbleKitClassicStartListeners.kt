package io.rebble.libpebblecommon.pebblekit.classic

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.pebblekit.PebbleKitSurface
import io.rebble.libpebblecommon.pebblekit.pebbleKitSurfaceEnabled
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

private val logger = Logger.withTag("PebbleKitClassicStartListeners")

/** Fork: the exported START/STOP receivers are registered only while classic PebbleKit is on. */
class PebbleKitClassicStartListeners(
    private val classicEnabled: Flow<Boolean>,
    private val startRequests: Flow<Uuid>,
    private val stopRequests: Flow<Uuid>,
    private val launchApp: suspend (Uuid) -> Unit,
    private val stopApp: suspend (Uuid) -> Unit,
    private val scope: CoroutineScope,
) {
    fun init() {
        scope.launch {
            classicEnabled.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled) {
                    logger.d { "Classic PebbleKit is off; START/STOP receivers stay unregistered" }
                    return@collectLatest
                }
                // The next toggle value cancels both collections, which unregisters both receivers.
                coroutineScope {
                    launch {
                        startRequests.collect { uuid ->
                            logger.d { "Got app start: $uuid" }
                            launchApp(uuid)
                        }
                    }
                    launch {
                        stopRequests.collect { uuid ->
                            logger.d { "Got app stop: $uuid" }
                            stopApp(uuid)
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            libPebble: LibPebble,
            coroutineScope: LibPebbleCoroutineScope,
            watchConfig: WatchConfigFlow,
        ) = PebbleKitClassicStartListeners(
            classicEnabled = classicEnabled(watchConfig),
            startRequests = IntentFilter(INTENT_APP_START).asFlow(context, exported = true)
                .mapNotNull { it.watchappUuidExtra() },
            stopRequests = IntentFilter(INTENT_APP_STOP).asFlow(context, exported = true)
                .mapNotNull { it.watchappUuidExtra() },
            launchApp = libPebble::launchApp,
            stopApp = libPebble::stopApp,
            scope = coroutineScope,
        )

        internal fun classicEnabled(watchConfig: WatchConfigFlow): Flow<Boolean> =
            watchConfig.pebbleKitSurfaceEnabled(PebbleKitSurface.Classic)
    }
}

/** The watchapp UUID, as a java UUID or its string; a read that throws counts as none (START and STOP have no other catch). */
internal fun Intent.watchappUuidExtra(): Uuid? = watchappUuidFrom { getSerializableExtra(APP_UUID) }

internal fun watchappUuidFrom(read: () -> Serializable?): Uuid? =
    handleSenderInput(
        // The throwable's class only: its message can quote a class name the sender chose.
        onDropped = { logger.w { "Ignoring a watchapp UUID extra that cannot be read: ${it::class.simpleName}" } },
    ) { read()?.asUuid() }

fun Serializable.asUuid(): Uuid? = (this as? UUID)?.toKotlinUuid() ?: (this as? String)?.let {
    try {
        Uuid.parse(it)
    } catch (e: IllegalArgumentException) {
        // Fork: the length only. The string is the sending app's, and log lines are not escaped.
        logger.w { "Failed to parse a UUID string of ${it.length} characters" }
        null
    }
}

/**
 * Intent broadcast to pebble.apk responsible for launching a watch-app on the connected watch. This intent is
 * idempotent.
 */
private const val INTENT_APP_START = "com.getpebble.action.app.START"

/**
 * Intent broadcast to pebble.apk responsible for closing a running watch-app on the connected watch. This intent is
 * idempotent.
 */
private const val INTENT_APP_STOP = "com.getpebble.action.app.STOP"


/**
 * The bundle-key used to store a message's UUID.
 */
private const val APP_UUID = "uuid"
