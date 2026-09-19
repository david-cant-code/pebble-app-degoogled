package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

// Fork: event vocabulary for the serialized companion session stream.
internal sealed class SessionEvent {
    data class AppChanged(val uuid: Uuid?) : SessionEvent()
    data class RestartRequested(val uuid: Uuid, val generation: Long) : SessionEvent()
}

/**
 * Fork: the decision layer for companion app session lifecycle. Watch-side app
 * changes and session watchers' restart requests are merged into one serially
 * collected stream: both kinds of event tear down and rebuild the same session
 * state, so they must never interleave. The effects (stopping and starting real
 * sessions) are injected, because they live in CompanionAppLifecycleManager among
 * Room- and WebView-backed machinery a unit test cannot construct; every decision
 * about when they run lives here, pinned by CompanionSessionCoordinatorTest.
 *
 * Decision model:
 *  - [SessionEvent.AppChanged] is conflated to the newest watch state: an event is
 *    skipped when it equals the last processed value (a brief switch away and back
 *    must not restart a surviving session) or when it no longer matches the latest
 *    running app (each stale app would otherwise get a full session build and
 *    teardown, PBW download and WebView included, delaying the app actually in the
 *    foreground).
 *  - [SessionEvent.RestartRequested] is honoured only when it still targets the
 *    live session, checked as app uuid AND session generation: a dying session's
 *    watcher can race its request past teardown, and by the time the request is
 *    processed a fresh session of the same app may already be running with the
 *    change in place, which the uuid alone cannot detect.
 */
internal class CompanionSessionCoordinator(
    private val latestRunningApp: () -> Uuid?,
    private val currentSessionApp: () -> Uuid?,
    private val stopSession: suspend () -> Unit,
    private val startSession: suspend (Uuid) -> Unit,
) {
    private val logger = Logger.withTag("CompanionSessionCoordinator")

    // Restart requests raised by session-bound watchers. DROP_OLDEST
    // because restart requests are idempotent for the session they target.
    private val restartRequests = MutableSharedFlow<SessionEvent.RestartRequested>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Identifies the session most recently started, so a restart request can be
     * pinned to the session that raised it. Read it when launching a session-bound
     * watcher and pass that value to [requestRestart]; reading it any later (in
     * particular inside the watcher's emission handler) could adopt a successor
     * session's generation and defeat the staleness check.
     */
    var currentGeneration: Long = 0L
        private set

    // Last AppChanged value that passed the skip checks. Deliberately tracks what
    // was processed, not what is running: a value must be considered handled even
    // if the session it started later failed, matching how a conflating collector
    // treats a delivered value.
    private var lastProcessedApp: Uuid? = null

    fun requestRestart(uuid: Uuid, generation: Long) {
        restartRequests.tryEmit(SessionEvent.RestartRequested(uuid, generation))
    }

    /** Collects the merged event stream until the caller's scope dies. */
    suspend fun run(runningApp: Flow<Uuid?>) {
        merge(
            runningApp.map { SessionEvent.AppChanged(it) },
            restartRequests,
        )
            .onEach { process(it) }
            .onCompletion {
                // Unsure if this is needed
                stopSession()
            }
            .collect()
    }

    // Called only from the single collector in [run]; the decision state above is
    // confined to that collector.
    internal suspend fun process(event: SessionEvent) {
        when (event) {
            is SessionEvent.AppChanged -> {
                if (event.uuid == lastProcessedApp) return
                if (event.uuid != latestRunningApp()) {
                    logger.d { "Skipping stale app change to ${event.uuid}" }
                    return
                }
                lastProcessedApp = event.uuid
                stopSession()
                if (event.uuid != null) {
                    currentGeneration++
                    startSession(event.uuid)
                }
            }

            is SessionEvent.RestartRequested -> {
                if (currentSessionApp() != event.uuid) return
                if (currentGeneration != event.generation) return
                logger.d { "Restarting companion apps for ${event.uuid} at a session watcher's request" }
                stopSession()
                currentGeneration++
                startSession(event.uuid)
            }
        }
    }
}

/**
 * Emits once each time the upstream grant differs from the value before it, starting from
 * [builtWith], the grant the session was built with. Repeated equal values are ignored: the
 * resolved grant flow re-emits on unrelated permission-table and config writes.
 */
internal fun Flow<Boolean>.grantChanges(builtWith: Boolean): Flow<Unit> = flow {
    var previous = builtWith
    collect { allowed ->
        if (previous != allowed) {
            emit(Unit)
        }
        previous = allowed
    }
}

/**
 * Requests a restart of [app]'s session [sessionGeneration] on each [grantChanges] emission
 * of its Network grant; a generation read at emission could be a successor session's.
 */
internal fun CoroutineScope.launchNetworkGrantWatcher(
    grant: Flow<Boolean>,
    builtWith: Boolean,
    app: Uuid,
    sessionGeneration: Long,
    requestRestart: (app: Uuid, generation: Long) -> Unit,
): Job = launch {
    grant.grantChanges(builtWith).collect {
        watcherLogger.d { "Network grant for $app changed mid-session; requesting restart" }
        requestRestart(app, sessionGeneration)
    }
}

private val watcherLogger = Logger.withTag("NetworkGrantWatcher")
