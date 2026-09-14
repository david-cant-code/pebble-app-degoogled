package io.rebble.libpebblecommon.pebblekit

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private val logger = Logger.withTag("PebbleKitSurface")

/** Fork: the two Android companion surfaces; the routing rule is here so the session factory and gate share it. */
enum class PebbleKitSurface { Classic, PebbleKit2 }

/** PebbleKit 2 when the appinfo names an Android companion package; classic otherwise, JS-only watchfaces included. */
fun PbwAppInfo.pebbleKitSurface(): PebbleKitSurface =
    if (companionApp?.android?.apps?.any { it.pkg != null } == true) {
        PebbleKitSurface.PebbleKit2
    } else {
        PebbleKitSurface.Classic
    }

fun WatchConfig.isPebbleKitSurfaceEnabled(surface: PebbleKitSurface): Boolean = when (surface) {
    PebbleKitSurface.Classic -> classicPebbleKitEnabled
    PebbleKitSurface.PebbleKit2 -> pebbleKit2Enabled
}

fun WatchConfigFlow.pebbleKitSurfaceEnabled(surface: PebbleKitSurface): Flow<Boolean> =
    flow.map { it.watchConfig.isPebbleKitSurfaceEnabled(surface) }

/** Upstream's multiple-companions rule, and the watchapp's own surface must be on; it never gets the other surface. */
fun WatchConfig.allowsPlatformCompanionSession(appInfo: PbwAppInfo, pkjsRunning: Boolean): Boolean {
    if (pkjsRunning && !appMessageToMultipleCompanions) return false
    return isPebbleKitSurfaceEnabled(appInfo.pebbleKitSurface())
}

/**
 * Emits each time the session decision for [appInfo] stops matching [builtWith], the one the
 * running session was built with, not the first value collected: the collector subscribes after
 * the sessions start, and a flip in between must still count. A flip off and back on across a
 * session's own start() is not seen (KNOWN_ISSUES).
 */
fun Flow<LibPebbleConfig>.platformSessionGateChanges(
    appInfo: PbwAppInfo,
    pkjsRunning: Boolean,
    builtWith: Boolean,
): Flow<Unit> = map { it.watchConfig.allowsPlatformCompanionSession(appInfo, pkjsRunning) }
    .distinctUntilChanged()
    .filter { it != builtWith }
    .map { }

/**
 * Requests a restart of [app]'s session [sessionGeneration] on each [platformSessionGateChanges]
 * emission; a generation read at emission could be a successor session's.
 */
internal fun CoroutineScope.launchPlatformSessionGateWatcher(
    config: Flow<LibPebbleConfig>,
    appInfo: PbwAppInfo,
    pkjsRunning: Boolean,
    builtWith: Boolean,
    app: Uuid,
    sessionGeneration: Long,
    requestRestart: (app: Uuid, generation: Long) -> Unit,
): Job = launch {
    config.platformSessionGateChanges(appInfo, pkjsRunning, builtWith).collect {
        logger.d { "PebbleKit toggle changed mid-session; requesting restart of $app" }
        requestRestart(app, sessionGeneration)
    }
}
