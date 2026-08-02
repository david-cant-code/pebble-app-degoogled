package io.rebble.libpebblecommon.pebblekit.two

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.RemoteTimelineEmulator
import io.rebble.libpebblecommon.js.TimelineLayoutJson
import io.rebble.libpebblecommon.js.TimelinePinJson
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleSenderReceiver
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.collections.mapNotNull
import kotlin.collections.orEmpty
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlin.time.toKotlinInstant
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val WATCH_SENDING_TIMEOUT = 10.seconds

private val logger = Logger.withTag("PebbleSenderReceiver")

// The request protocol is internal to the PebbleKit server library and is not exposed as public
// API, so these are pinned by PebbleKitRequestProtocolTest rather than by the compiler. If that
// test starts failing after a library upgrade, the wire keys changed and the checks below are
// silently inert until they are updated to match.
private const val KEY_ACTION = "ACTION"
private const val KEY_WATCHAPP_UUID = "WATCHAPP_UUID"
private const val KEY_WATCHES_ID = "WATCHES_ID"
private const val KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS"
private const val ACTION_START_APP = "START_APP"
private const val ACTION_STOP_APP = "STOP_APP"

class PebbleSenderReceiver : BasePebbleSenderReceiver(), LibPebbleKoinComponent {
    private val watchManager: Watches = getKoin().get<LibPebble>()
    private val locker: Locker = getKoin().get()
    private val lockerPBWCache: LockerPBWCache = getKoin().get()
    private val remoteTimelineEmulator: RemoteTimelineEmulator = getKoin().get()
    private val companionRegistry: PebbleKitCompanionRegistry = getKoin().get()
    private val watchIdentity: PebbleKitWatchIdentity = getKoin().get()
    override val coroutineScope: LibPebbleCoroutineScope = getKoin().get()

    /**
     * Wraps the base binder so requests can be inspected while the caller's identity is still
     * available.
     *
     * [startAppOnTheWatch] and [stopAppOnTheWatch] receive no caller identity at all, unlike the
     * app-message and timeline entry points, so on their own they cannot tell an authorized
     * companion from any other app that happens to be installed. The library does resolve the
     * calling package, on the binder thread where it is authoritative, and then discards it
     * before invoking those two callbacks. Intercepting here is what puts it back.
     *
     * Everything is delegated synchronously on that same binder thread on purpose: dispatching
     * to a coroutine first would make the library's own caller lookup return this app's identity
     * instead of the client's, silently defeating the checks it already performs.
     */
    override fun onBind(intent: Intent?): IBinder? {
        val delegate = super.onBind(intent) as? UniversalRequestResponse ?: return null
        return AuthorizingBinder(delegate)
    }

    private inner class AuthorizingBinder(
        private val delegate: UniversalRequestResponse,
    ) : UniversalRequestResponse.Stub() {

        override fun request(request: Bundle, callback: SendDataCallback) {
            val caller = packageManager.getNameForUid(getCallingUid())
            if (caller == null) {
                logger.d { "Rejecting PebbleKit request from an unresolvable caller" }
                callback.replyEmpty()
                return
            }

            val action = request.getString(KEY_ACTION)
            if (action == ACTION_START_APP || action == ACTION_STOP_APP) {
                val watchapp = request.getString(KEY_WATCHAPP_UUID)
                if (!companionRegistry.isAuthorizedFor(caller, watchapp)) {
                    logger.d { "Denied $action of $watchapp from $caller" }
                    callback.replyEmpty()
                    return
                }
            }

            // Callers only ever saw pseudonyms, so translate before the base class matches them
            // against real serials, and remember the mapping to undo it on the way back.
            val serials = connectedSerials()
            val supplied = request.getStringArray(KEY_WATCHES_ID)
            val serialBySupplied = supplied.orEmpty().associateWith { identifier ->
                watchIdentity.resolveSerial(caller, identifier, serials) ?: identifier
            }
            val outbound = if (supplied == null) {
                request
            } else {
                Bundle(request).apply {
                    putStringArray(KEY_WATCHES_ID, supplied.map { serialBySupplied.getValue(it) }.toTypedArray())
                }
            }
            val suppliedBySerial = serialBySupplied.entries.associate { (k, v) -> v to k }

            runCatching {
                delegate.request(outbound, object : SendDataCallback.Stub() {
                    override fun onResult(result: Bundle) {
                        callback.onResult(pseudonymiseResults(result, caller, suppliedBySerial))
                    }
                })
            }.onFailure {
                logger.e(it) { "Failed to dispatch PebbleKit request" }
                // Same contract as the rejection paths above: the caller always hears back.
                // Swallowing the failure without replying would leave it blocked on a callback
                // that will never fire.
                callback.replyEmpty()
            }
        }
    }

    /**
     * Rewrites the per-watch result keys, which the base class fills in with real serials.
     * The key translation itself lives in [pseudonymiseTransmissionKeys]; this method is only
     * the Bundle plumbing around it.
     */
    private fun pseudonymiseResults(
        result: Bundle,
        callingPackage: String,
        suppliedBySerial: Map<String, String>,
    ): Bundle {
        val results = result.getBundle(KEY_TRANSMISSION_RESULTS) ?: return result
        val identifierBySerial = pseudonymiseTransmissionKeys(
            serials = results.keySet(),
            suppliedBySerial = suppliedBySerial,
        ) { serial -> watchIdentity.pseudonymFor(callingPackage, serial) }
        val mapped = Bundle()
        identifierBySerial.forEach { (serial, identifier) ->
            results.getBundle(serial)?.let { mapped.putBundle(identifier, it) }
        }
        return Bundle(result).apply { putBundle(KEY_TRANSMISSION_RESULTS, mapped) }
    }

    private fun connectedSerials(): List<String> =
        watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>()
            .map { it.watchInfo.serial }

    private fun SendDataCallback.replyEmpty() {
        runCatching { onResult(Bundle()) }
            .onFailure { logger.e(it) { "Failed to reply to a rejected PebbleKit request" } }
    }

    override suspend fun sendDataToPebble(
        callingPackage: String?,
        watchappUUID: UUID,
        data: PebbleDictionary,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) { watch ->
            val companionApp = watch.currentCompanionAppSessions.value.filterIsInstance<PebbleKit2>().firstOrNull()

            if (companionApp == null || companionApp.uuid.toJavaUuid() != watchappUUID) {
                return@runOnConnectedWatches TransmissionResult.FailedDifferentAppOpen
            }

            if (callingPackage == null ||
                !companionApp.isAllowedToCommunicate(callingPackage)
            ) {
                return@runOnConnectedWatches TransmissionResult.FailedNoPermissions
            }

            companionApp.sendMessage(data)
        }
    }

    override suspend fun startAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) {
            it.launchApp(watchappUUID.toKotlinUuid())
            TransmissionResult.Success
        }
    }

    override suspend fun stopAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) {
            it.stopApp(watchappUUID.toKotlinUuid())
            TransmissionResult.Success
        }
    }

    override suspend fun insertTimelinePin(
        callingPackage: String?,
        watchappUUID: UUID,
        timelinePin: TimelinePin
    ): TimelineResult {
        if (!isAllowedToCommunicate(callingPackage, watchappUUID)) {
            return TimelineResult.FailedNoPermissions
        }

        remoteTimelineEmulator.insertPin(watchappUUID.toKotlinUuid(), timelinePin.toPinJson())
        return TimelineResult.Success
    }

    override suspend fun deleteTimelinePin(
        callingPackage: String?,
        watchappUUID: UUID,
        id: String
    ): TimelineResult {
        if (!isAllowedToCommunicate(callingPackage, watchappUUID)) {
            return TimelineResult.FailedNoPermissions
        }

        val success = remoteTimelineEmulator.deletePin(watchappUUID.toKotlinUuid(), id)
        return if (success) {
            TimelineResult.Success
        } else {
            TimelineResult.FailedUnknownPin
        }
    }

    private inline suspend fun runOnConnectedWatches(
        watches: List<WatchIdentifier>?,
        crossinline action: suspend (ConnectedPebbleDevice) -> TransmissionResult
    ): Map<WatchIdentifier, TransmissionResult> {
        val connectedWatches = watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>()

        val targetWatches = if (watches == null) {
            connectedWatches.map { WatchIdentifier(it.watchInfo.serial) }
        } else {
            watches
        }

        return coroutineScope {
            targetWatches.associateWith { targetWatchId ->
                async {
                    val watch = connectedWatches.firstOrNull { it.serial == targetWatchId.value }
                        ?: return@async TransmissionResult.FailedWatchNotConnected

                    try {
                        withTimeout(WATCH_SENDING_TIMEOUT) {
                            action(watch)
                        }
                    } catch (e: TimeoutCancellationException) {
                        TransmissionResult.FailedTimeout
                    }
                }
            }.mapValues { it.value.await() }
        }
    }

    private suspend fun isAllowedToCommunicate(pkg: String?, uuid: UUID): Boolean {
        if (pkg == null) {
            return false
        }

        val lockerEntry = locker.getLockerApp(uuid.toKotlinUuid()).firstOrNull() ?: return false
        val pbwInfo = PbwApp(
            lockerPBWCache.getPBWFileForApp(
                lockerEntry.properties.id,
                lockerEntry.properties.version.orEmpty(),
                locker
            )
        )

        return pbwInfo.info.companionApp?.android?.apps.orEmpty().any { it.pkg == pkg }
    }
}

/**
 * Maps each real serial in a result to the identifier the caller should see.
 *
 * An identifier the caller supplied is echoed back verbatim so it can still correlate the
 * result with its request. Anything else, which is the "all connected watches" case where the
 * base class generated the keys itself, is replaced with this caller's pseudonym, and dropped
 * outright if none can be derived: a failure in the identity layer must degrade into a missing
 * row, never into disclosing the serial it was meant to replace.
 */
internal fun pseudonymiseTransmissionKeys(
    serials: Collection<String>,
    suppliedBySerial: Map<String, String>,
    pseudonymFor: (String) -> String?,
): Map<String, String> = buildMap {
    serials.forEach { serial ->
        val identifier = suppliedBySerial[serial] ?: pseudonymFor(serial) ?: return@forEach
        put(serial, identifier)
    }
}

private fun TimelinePin.toPinJson(): TimelinePinJson {
    return TimelinePinJson(
        id,
        startTime,
        duration?.inWholeMinutes?.toInt(),
        layout = TimelineLayoutJson(
            type  = layout.type.code,
            title = layout.title,
            subtitle = layout.subtitle,
            body = layout.body,
            tinyIcon = layout.tinyIcon,
            smallIcon = layout.smallIcon,
            largeIcon = layout.largeIcon,
            primaryColor = layout.primaryColor,
            secondaryColor = layout.secondaryColor,
            backgroundColor = layout.backgroundColor,
            headings = layout.headings,
            paragraphs = layout.paragraphs,
            lastUpdated = layout.lastUpdated,
        )
    )
}
