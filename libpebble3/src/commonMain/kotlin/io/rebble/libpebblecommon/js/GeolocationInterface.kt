package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.ktor.http.quote
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

abstract class GeolocationInterface(
    private val scope: CoroutineScope,
    private val jsRunner: JsRunner,
): LibPebbleKoinComponent {
    private val logger = Logger.withTag("GeolocationInterface")
    private val watchappPermissions: WatchappPermissionResolver by inject()
    private val systemGeolocation: SystemGeolocation by inject()
    private var requestIDs = (1..Int.MAX_VALUE).iterator()
    private var watchIDs = (1..Int.MAX_VALUE).iterator()
    private val watchJobs = mutableMapOf<Int, Job>()

    private fun getNextRequestID(): Int {
        return if (requestIDs.hasNext()) {
            requestIDs.next()
        } else {
            requestIDs = (0..Int.MAX_VALUE).iterator()
            requestIDs.next()
        }
    }

    private fun getNextWatchID(): Int {
        return if (watchIDs.hasNext()) {
            watchIDs.next()
        } else {
            watchIDs = (0..Int.MAX_VALUE).iterator()
            watchIDs.next()
        }
    }

    private suspend fun triggerPositionResultGet(id: Int, result: GeolocationPositionResult) {
        when (result) {
            is GeolocationPositionResult.Success -> {
                Logger.i { "Geolocation get position success" }
                jsRunner.eval("_PebbleGeoCB._resultGetSuccess($id, ${result.latitude}, ${result.longitude}, ${result.accuracy}, ${result.altitude}, ${result.heading}, ${result.speed})")
            }
            is GeolocationPositionResult.Error -> {
                Logger.w { "Geolocation get position error: ${result.message}" }
                jsRunner.eval("_PebbleGeoCB._resultGetError($id, ${result.message.quote()})")
            }
        }
    }

    private suspend fun triggerPositionResultWatch(id: Int, result: GeolocationPositionResult) {
        when (result) {
            is GeolocationPositionResult.Success -> {
                jsRunner.eval("_PebbleGeoCB._resultWatchSuccess($id, ${result.latitude}, ${result.longitude}, ${result.accuracy}, ${result.altitude}, ${result.heading}, ${result.speed})")
            }
            is GeolocationPositionResult.Error -> {
                jsRunner.eval("_PebbleGeoCB._resultWatchError($id, ${result.message.quote()})")
            }
        }
    }

    // Fork: resolve the app's Location grant through the tri-state model (per-app
    // override, else the global default). Upstream read a per-app row that nothing
    // ever wrote and treated a missing row as "granted", so the gate was inert and
    // every watchapp got phone GPS silently. Now a missing/FollowGlobal app inherits
    // the deny-by-default global, and denial is reported back to the JS callback as a
    // geolocation error rather than being swallowed.
    private suspend fun geolocationPermissionGranted(): Boolean =
        watchappPermissions.isWatchappPermissionGranted(
            Uuid.parse(jsRunner.appInfo.uuid),
            LockerAppPermissionType.Location,
        )

    open fun getRequestCallbackID() = getNextRequestID()
    open fun getWatchCallbackID() = getNextWatchID()

    open fun getCurrentPosition(
        id: Double,
        maximumAgeMs: Double,
        timeoutMs: Double,
        highAccuracy: Double,
    ): Int {
        logger.d { "getCurrentPosition(maximumAgeMs=$maximumAgeMs, timeoutMs=$timeoutMs, highAccuracy=$highAccuracy)" }
        val maxAge: Duration? = if (maximumAgeMs >= 0) maximumAgeMs.toLong().milliseconds else null
        val timeout: Duration? = if (timeoutMs >= 0) timeoutMs.toLong().milliseconds else null
        val highAccuracyBool = highAccuracy > 0
        scope.launch {
            if (!geolocationPermissionGranted()) {
                Logger.w { "Watchapp location permission not granted for getCurrentPosition" }
                triggerPositionResultGet(id.toInt(), GeolocationPositionResult.Error("Location permission not granted"))
                return@launch
            }
            triggerPositionResultGet(id.toInt(), systemGeolocation.getCurrentPosition(maxAge, timeout, highAccuracyBool))
        }
        return id.toInt()
    }

    open fun watchPosition(id: Double, interval: Double, highAccuracy: Double): Int {
        logger.d { "watchPosition(highAccuracy=$highAccuracy)" }
        val highAccuracyBool = highAccuracy > 0
        val job = scope.launch {
            // The grant is enforced for the life of the watch, not only at
            // subscription time: a watchface can hold a subscription for days, so a
            // one-shot check would keep streaming phone GPS long after the user
            // revoked access. collectLatest on the resolved grant cancels the
            // in-flight GPS stream the moment the grant flips to deny (per-app
            // override or global default) and restarts it if the grant returns.
            // Denial is reported to the JS callback each time it takes effect; the
            // watch itself stays registered until the app clears it, matching
            // geolocation-spec behaviour for a watch awaiting permission.
            watchappPermissions.watchappPermissionGranted(
                Uuid.parse(jsRunner.appInfo.uuid),
                LockerAppPermissionType.Location,
            )
                .distinctUntilChanged()
                .collectLatest { granted ->
                    if (!granted) {
                        triggerPositionResultWatch(id.toInt(), GeolocationPositionResult.Error("Location permission not granted"))
                    } else {
                        systemGeolocation.watchPosition(interval.coerceAtLeast(200.0).milliseconds, highAccuracyBool).collect { result ->
                            triggerPositionResultWatch(id.toInt(), result)
                        }
                    }
                }
        }
        registerWatchJob(id.toInt(), job)
        return id.toInt()
    }

    private fun registerWatchJob(id: Int, job: Job) {
        // A reused id replaces its previous registration, so the replaced job must
        // be cancelled here or its grant collector would keep running unreachable
        // for the rest of the session.
        watchJobs.remove(id)?.cancel("Watch replaced")
        // Evict oldest-first at the cap so an app that retries watchPosition with
        // fresh ids after each error (and never calls clearWatch) converges to its
        // newest registrations instead of accumulating collectors for a session
        // that can last days.
        while (watchJobs.size >= MAX_ACTIVE_WATCHES) {
            val oldest = watchJobs.keys.first()
            logger.w { "watchPosition: evicting watch $oldest, limit is $MAX_ACTIVE_WATCHES active watches" }
            watchJobs.remove(oldest)?.cancel("Watch evicted")
        }
        watchJobs[id] = job
    }

    open fun clearWatch(id: Int) {
        logger.d { "clearWatch()" }
        watchJobs.remove(id)?.cancel("Watch cleared")
    }

    companion object {
        // Hard cap on concurrently registered watchPosition watches per session.
        // Every registration pins a live grant collector until it is cleared or
        // the session ends (denied watches included, so a re-grant can resume
        // them), and the bridge is callable from arbitrary page JS, so without a
        // cap a retry loop grows the collector set for the whole session. Sixteen
        // is far above any legitimate concurrent use.
        internal const val MAX_ACTIVE_WATCHES = 16
    }
}