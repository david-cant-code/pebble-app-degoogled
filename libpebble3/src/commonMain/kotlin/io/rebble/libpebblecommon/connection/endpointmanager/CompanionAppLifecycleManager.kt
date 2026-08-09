package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class CompanionAppLifecycleManager(
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryRealDao,
    private val appRunStateService: AppRunStateService,
    private val appMessagesService: AppMessageService,
    private val locker: Locker,
    private val connectionScope: ConnectionCoroutineScope,
    private val libPebbleConfigFlow: LibPebbleConfigFlow,
    private val libpebbleCoroutineScope: LibPebbleCoroutineScope,
    private val watchappPermissions: WatchappPermissionResolver,
): ConnectedPebble.PKJS, ConnectedPebble.CompanionAppControl {
    companion object {
        private val logger = Logger.withTag(CompanionAppLifecycleManager::class.simpleName!!)
    }

    private lateinit var device: CompanionAppDevice

    private var activeAppScope: CoroutineScope = CoroutineScope(Job().also { it.cancel() })

    // Fork: the locker entry whose companion apps are currently running, kept so a
    // permission-triggered restart can verify the request still targets the live
    // session before acting on it.
    private var currentEntry: LockerEntry? = null

    // Fork: every decision about when sessions stop, start, and restart lives in
    // the coordinator (see its KDoc for the model); this class only supplies the
    // effects, which need the locker DAO, PBW cache, and WebView machinery that
    // unit tests cannot construct.
    private val sessionCoordinator = CompanionSessionCoordinator(
        latestRunningApp = { appRunStateService.runningApp.value },
        currentSessionApp = { currentEntry?.id },
        stopSession = { handleAppStop() },
        startSession = { uuid ->
            // Fresh lookup on every start so a permission restart also picks up the
            // newest locker entry for the app.
            val lockerEntry = lockerEntryDao.getEntry(uuid)
            if (lockerEntry != null && !lockerEntry.systemApp) {
                handleNewRunningApp(lockerEntry)
            }
        },
    )

    private val runningApps: MutableStateFlow<List<CompanionApp>> = MutableStateFlow(emptyList())
    @Deprecated("Use more generic currentCompanionAppSession instead and cast if necessary")
    override val currentPKJSSession: StateFlow<PKJSApp?> = PKJSStateFlow(runningApps)

    override val currentCompanionAppSessions: StateFlow<List<CompanionApp>>
        get() = runningApps.asStateFlow()

    private suspend fun handleAppStop() {
        activeAppScope.cancel()
        currentEntry = null
        runningApps.value.forEach { app ->
            // Don't error out if one app fails to stop
            try {
                app.stop()
            } catch (e: Exception) {
                logger.e(e) { "Error stopping companion app: ${e.message}" }
            }
        }
        runningApps.value = emptyList()
    }

    private suspend fun handleNewRunningApp(lockerEntry: LockerEntry) {
        try {
            currentEntry = lockerEntry
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(throwable) { "Unhandled exception in CompanionAppLifecycleManager-${lockerEntry.id}: ${throwable.message}" }
            }
            activeAppScope = connectionScope +
                    Job() +
                    CoroutineName("CompanionAppLifecycleManager-${lockerEntry.id}") +
                    exceptionHandler

            val pbw = PbwApp(lockerPBWCache.getPBWFileForApp(lockerEntry.id, lockerEntry.version, locker))
            if (runningApps.value.isNotEmpty()) {
                logger.w { "App ${lockerEntry.id} is already running, stopping it before starting a new one" }
                runningApps.value.forEach { it.stop() }
            }

            val newApps = createCompanionApps(pbw, lockerEntry)
            runningApps.value = newApps

            val appIncomingChannels = newApps.map { Channel<AppMessageData>(Channel.BUFFERED) }

            newApps.zip(appIncomingChannels).forEach { (app, channel) ->
                app.start(channel.receiveAsFlow())
            }

            activeAppScope.launch {
                device.inboundAppMessages(lockerEntry.id).collect { message ->
                    for (channel in appIncomingChannels) {
                        channel.trySend(message)
                    }
                }
            }

            // Fork: restart the session when the app's Network grant flips from deny
            // to allow. A PKJS session that loaded while denied had its JS network
            // entry points guarded from page load, and an app that only fetches at
            // launch never touches the network again after that first attempt throws,
            // so a mid-session grant would otherwise take visible effect only at the
            // next app switch. Restarting re-runs the app's JS with the grant in
            // place, which is the same transition apps already handle when the watch
            // switches away and back. The allow-to-deny direction needs no restart:
            // the enforcement layers apply it live. The watcher dies with
            // activeAppScope, and a fresh session's watcher starts with a clean
            // transition history, so a restart cannot retrigger itself.
            if (newApps.any { it is PKJSApp }) {
                // Read here, where the coordinator has already counted this
                // session's start, so the request stays pinned to THIS session; a
                // read at emission time could adopt a successor session's
                // generation and defeat the coordinator's staleness check.
                val sessionGeneration = sessionCoordinator.currentGeneration
                activeAppScope.launch {
                    watchappPermissions
                        .watchappPermissionGranted(lockerEntry.id, LockerAppPermissionType.Network)
                        .denyToAllowTransitions()
                        .collect {
                            logger.d { "Network grant for ${lockerEntry.id} allowed mid-session; requesting restart" }
                            sessionCoordinator.requestRestart(lockerEntry.id, sessionGeneration)
                        }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to init Companion app for app ${lockerEntry.id}: ${e.message}" }
            handleAppStop()
            return
        }
    }

    private fun createCompanionApps(
        pbw: PbwApp,
        lockerEntry: LockerEntry
    ): List<CompanionApp> {
        return buildList {
            val pkjsApp = if (pbw.hasPKJS) {
                val jsPath = lockerPBWCache.getPKJSFileForApp(lockerEntry.id, lockerEntry.version)
                PKJSApp(
                    device,
                    jsPath,
                    pbw.info,
                    lockerEntry,
                    connectionScope,
                )
            } else null
            pkjsApp?.let { add(it) }
            if (libPebbleConfigFlow.value.watchConfig.appMessageToMultipleCompanions || pkjsApp == null) {
                createPlatformSpecificCompanionAppControl(
                    device = device,
                    appInfo = pbw.info,
                    pkjsRunning = pkjsApp != null,
                    connectionCoroutineScope = connectionScope,
                    libPebbleCoroutineScope = libpebbleCoroutineScope,
                )?.let {
                    add(it)
                }
            }
        }
    }

    fun init(identifier: PebbleIdentifier, watchInfo: WatchInfo) {
        this.device = CompanionAppDevice(
            identifier,
            watchInfo,
            appMessagesService
        )
        // Fork: session decisions live in CompanionSessionCoordinator; this wiring
        // stays thin so upstream merges only touch the effect lambdas above.
        connectionScope.launch {
            sessionCoordinator.run(appRunStateService.runningApp)
        }
    }
}

/**
 * Hack to keep backwards compatibilty with the old ConnectedPebble.PKJS interface. It creates a state flow that only
 * exposes PKJSApp instances
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class PKJSStateFlow(private val runningAppStateFlow: StateFlow<List<CompanionApp>>): StateFlow<PKJSApp?> {
    override val value: PKJSApp?
        get() = runningAppStateFlow.value.filterIsInstance<PKJSApp>().firstOrNull()
    override val replayCache: List<PKJSApp?>
        get() = runningAppStateFlow.replayCache.map { it.filterIsInstance<PKJSApp>().firstOrNull() }

    override suspend fun collect(collector: FlowCollector<PKJSApp?>): Nothing {
        runningAppStateFlow.map { it.filterIsInstance<PKJSApp>().firstOrNull() }.collect(collector)
        throw IllegalStateException("This collect should never stop because parent is a state flow")
    }
}

expect fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo,
    pkjsRunning: Boolean,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
    connectionCoroutineScope: ConnectionCoroutineScope,
): CompanionApp?
