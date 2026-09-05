package coredevices.coreapp

import PlatformContext
import co.touchlab.kermit.Logger
import coredevices.coreapp.util.notifyLocal
import com.russhwolf.settings.Settings
import coredevices.CoreBackgroundSync
import coredevices.ExperimentalDevices
import coredevices.analytics.AnalyticsBackend
import coredevices.analytics.CoreAnalytics
import coredevices.analytics.setUser
import coredevices.coreapp.api.BugReports
import coredevices.coreapp.ui.screens.SHOWN_ONBOARDING
import coredevices.coreapp.util.AppUpdate
import coredevices.firestore.UsersDao
import coredevices.pebble.PebbleAppDelegate
import coredevices.pebble.account.FirestoreKnownWatchesSync
import coredevices.pebble.account.FirestoreLocker
import coredevices.pebble.health.PlatformHealthSync
import coredevices.pebble.services.PebbleAccountProvider
import coredevices.pebble.weather.WeatherFetcher
import coredevices.util.models.ModelManager
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.DoneInitialOnboarding
import coredevices.util.emailOrNull
import coredevices.util.models.CactusSTTMode
import coredevices.util.transcription.CactusModelPathProvider
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal const val STT_UPDATE_NOTIFIED_VERSION_KEY = "stt_update_notified_version"
internal const val STT_MODE_BEFORE_UPDATE_KEY = "stt_mode_before_update"
internal val STT_UPDATE_NOTIFICATION_ID = "stt_update_notif".hashCode()

private val sttMigrationLogger = Logger.withTag("SttModelMigration")

/**
 * Whether the voice activity detector should be fetched in the background:
 * only when at least one catalog speech model is installed (a device with
 * no local dictation has no use for it) and the detector itself is not.
 * Static so the decision is under host tests with a fake provider.
 */
internal fun vadDownloadNeeded(provider: CactusModelPathProvider): Boolean =
    !provider.isVadModelInstalled() &&
        provider.getDownloadedModels().any { provider.isModelDownloaded(it) }

/**
 * Startup pass over the on-device STT models; every install runs it on
 * every launch, and it is the only path that migrates a previous-engine
 * install. When stale installs exist (previous-engine directories, torn
 * catalog installs), the user's local mode is stashed under
 * [STT_MODE_BEFORE_UPDATE_KEY], config falls back to RemoteOnly with no
 * model, the stale directories are deleted, and [notifyUpgrade] fires once
 * per catalog generation (deduped via [STT_UPDATE_NOTIFIED_VERSION_KEY])
 * to point at the re-download. The stash is only resolved once a usable
 * catalog model is actually installed, so SttModelUpdatePrompt keeps
 * offering the download across launches until then; a user who picked a
 * local mode themselves in the meantime keeps their choice and the stash
 * is dropped as stale.
 *
 * Static with its dependencies handed in so the state machine stays under
 * host tests: it is destructive (deletes model directories) and runs
 * exactly once per user in the field, so a regression here is
 * unrecoverable and invisible.
 */
internal fun runSttModelMigration(
    modelProvider: CactusModelPathProvider,
    settings: Settings,
    coreConfigHolder: CoreConfigHolder,
    notifyUpgrade: () -> Unit,
) {
    val incompatible = modelProvider.getIncompatibleModels()
    if (incompatible.isNotEmpty()) {
        sttMigrationLogger.d { "Incompatible models found: $incompatible" }
        val currentMode = coreConfigHolder.config.value.sttConfig.mode
        if (currentMode != CactusSTTMode.RemoteOnly && !settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY)) {
            settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, currentMode.id)
        }
        coreConfigHolder.update(
            coreConfigHolder.config.value.copy(
                sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                    mode = coreConfigHolder.config.value.sttConfig.mode
                        .takeUnless { it.usesLocalCactus() } ?: CactusSTTMode.RemoteOnly,
                    modelName = null,
                )
            )
        )
        // Everything incompatible is deleted outright: an in-flight
        // catalog re-download is unaffected because its resumable
        // partial lives in the installer's staging dir, which is
        // never reported as a model.
        incompatible.forEach {
            try {
                modelProvider.deleteModel(it)
            } catch (e: Exception) {
                sttMigrationLogger.w(e) { "Failed to delete incompatible model $it" }
            }
        }
        if (settings.getStringOrNull(STT_UPDATE_NOTIFIED_VERSION_KEY) != WhisperModelCatalog.GENERATION) {
            settings.putString(STT_UPDATE_NOTIFIED_VERSION_KEY, WhisperModelCatalog.GENERATION)
            notifyUpgrade()
        }
    } else if (settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY)) {
        val mode = coreConfigHolder.config.value.sttConfig.mode
        if (mode != CactusSTTMode.RemoteOnly) {
            // The user picked a mode themselves since the sweep; the
            // stash is stale and must not override their choice.
            settings.remove(STT_MODE_BEFORE_UPDATE_KEY)
        } else {
            // Complete the restore only once a usable model exists
            // (downloaded via the prompt or Manage Offline Models).
            val installed = modelProvider.getDownloadedModels()
                .firstOrNull { modelProvider.isModelDownloaded(it) }
            if (installed != null) {
                val saved = CactusSTTMode.fromId(
                    settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.RemoteOnly.id)
                )
                settings.remove(STT_MODE_BEFORE_UPDATE_KEY)
                coreConfigHolder.update(
                    coreConfigHolder.config.value.copy(
                        sttConfig = coreConfigHolder.config.value.sttConfig.copy(
                            mode = saved,
                            modelName = installed,
                        )
                    )
                )
            }
        }
    }
}

class CommonAppDelegate(
    private val platformContext: PlatformContext,
    private val bugReports: BugReports,
    private val settings: Settings,
    private val doneInitialOnboarding: DoneInitialOnboarding,
    private val analyticsBackend: AnalyticsBackend,
    private val coreAnalytics: CoreAnalytics,
    private val pebbleAppDelegate: PebbleAppDelegate,
    private val appUpdate: AppUpdate,
    private val weatherFetcher: WeatherFetcher,
    private val experimentalDevices: ExperimentalDevices,
    private val coreConfigHolder: CoreConfigHolder,
    private val appContext: AppContext,
    private val usersDao: UsersDao,
    private val pebbleAccountProvider: PebbleAccountProvider,
    private val firestoreLocker: FirestoreLocker,
    private val firestoreKnownWatchesSync: FirestoreKnownWatchesSync,
    private val libPebble: LibPebble,
    private val platformHealthSync: PlatformHealthSync,
) : CoreBackgroundSync {
    private val logger = Logger.withTag("CommonAppDelegate")
    private val syncInProgress = MutableStateFlow(false)

    /**
     * Runs [runSttModelMigration] (the host-tested state machine) with the
     * production wiring: the DI-resolved provider and the platform
     * notification.
     */
    private fun initLocalSttModels() {
        val modelProvider = try {
            org.koin.mp.KoinPlatform.getKoin().get<CactusModelPathProvider>()
        } catch (e: Exception) {
            logger.w(e) { "STT model provider not available" }
            return
        }
        try {
            runSttModelMigration(modelProvider, settings, coreConfigHolder) {
                notifyLocal(
                    platformContext,
                    STT_UPDATE_NOTIFICATION_ID,
                    "Offline voice recognition",
                    "Offline voice recognition has been upgraded. Open the app to download the new model."
                )
            }
        } catch (e: Exception) {
            logger.w(e) { "STT model check skipped" }
        }
        // Installs that predate the voice activity detector have speech
        // models but no detector; fetch it once through the same verified,
        // notification-visible download job a model uses, on unmetered
        // networks only (885 KB, no consent prompt of its own).
        try {
            if (vadDownloadNeeded(modelProvider)) {
                org.koin.mp.KoinPlatform.getKoin().get<ModelManager>().downloadDetector(allowMetered = false)
            }
        } catch (e: Exception) {
            logger.w(e) { "Voice activity detector download not scheduled" }
        }
    }

    private fun oneTimeSetLockerOrderMode() {
        GlobalScope.launch {
            val key = "HAS_DONE_ONE_OFF_WATCHFACE_ORDER_SETTING"
            if (!settings.hasKey(key)) {
                val config = libPebble.config.value
                libPebble.updateConfig(
                    config.copy(
                        watchConfig = config.watchConfig.copy(
                            orderWatchfacesByLastUsed = true,
                        )
                    )
                )
                settings.putBoolean(key, true)
            }
        }
    }

    fun init() {
        usersDao.init()
        GlobalScope.launch(Dispatchers.Default) {
            usersDao.initUserDevToken(pebbleAccountProvider.get().devToken.value)
        }
        GlobalScope.launch(Dispatchers.Default) {
            Firebase.auth.currentUser?.emailOrNull?.let {
                analyticsBackend.setUser(email = it)
            }
        }
        initLocalSttModels()
        bugReports.init()
        GlobalScope.launch(Dispatchers.Default) {
            weatherFetcher.init()
            withContext(Dispatchers.Main) {
                experimentalDevices.init()
            }
        }
        firestoreLocker.init()
        firestoreKnownWatchesSync.init()
        oneTimeSetLockerOrderMode()
        platformHealthSync.startAutoSync(GlobalScope)
        if (settings.getBoolean(SHOWN_ONBOARDING, false)) {
            doneInitialOnboarding.onDoneInitialOnboarding()
        }
    }

    override suspend fun doBackgroundSync(scope: CoroutineScope, force: Boolean) {
        if (!syncInProgress.compareAndSet(false, true)) {
            logger.d { "Skipping background sync - already in progress" }
            return
        }
        // Use the background runtime window even if the sync intervals below haven't elapsed
        experimentalDevices.onBackgroundSync()
        val now = Clock.System.now()
        val config = coreConfigHolder.config.value
        val lastFullSync =
            Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_FULL_SYNC_MS, 0L))
        val lastPartialSync =
            Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_PARTIAL_SYNC_MS, 0L))
        // 0.9× slack absorbs scheduler/timer jitter
        val doFullSync =
            force || (now - lastFullSync) >= config.regularSyncInterval * 0.9
        val doPartialSync =
            doFullSync || (now - lastPartialSync) >= config.weatherSyncInterval * 0.9
        logger.d { "doBackgroundSync: doFullSync=$doFullSync doPartialSync=$doPartialSync" }
        if (!doPartialSync) {
            syncInProgress.value = false
            return
        }
        try {
            if (doFullSync) {
                settings.putLong(KEY_LAST_FULL_SYNC_MS, now.toEpochMilliseconds())
            }
            settings.putLong(KEY_LAST_PARTIAL_SYNC_MS, now.toEpochMilliseconds())
            val jobs = buildList {
                add(
                    scope.launch {
                        weatherFetcher.fetchWeather(scope)
                    }
                )
                add(
                    scope.launch {
                        platformHealthSync.sync()
                        libPebble.requestHealthData()
                    }
                )
                if (doFullSync) {
                    add(scope.launch {
                        coreAnalytics.processHeartbeat()
                    })
                    add(scope.launch {
                        pebbleAppDelegate.performBackgroundWork(scope)
                    })
                    add(scope.launch {
                        appUpdate.updateAvailable.value
                    })
                }
            }
            jobs.joinAll()
        } finally {
            syncInProgress.value = false
        }
        logger.d { "doBackgroundSync / finished doFullSync=$doFullSync" }
    }

    override suspend fun timeSinceLastSync(): Duration {
        val now = Clock.System.now()
        val lastFullSync = Instant.fromEpochMilliseconds(settings.getLong(KEY_LAST_FULL_SYNC_MS, 0L))
        return now - lastFullSync
    }

    override fun updateFullSyncPeriod(interval: Duration) {
        coreConfigHolder.update(
            coreConfigHolder.config.value.copy(
                regularSyncInterval = interval,
            )
        )
    }

    override fun updateWeatherSyncPeriod(interval: Duration) {
        coreConfigHolder.update(
            coreConfigHolder.config.value.copy(
                weatherSyncInterval = interval,
            )
        )
        rescheduleBgRefreshTask(appContext, coreConfigHolder.config.value)
    }
}

expect fun rescheduleBgRefreshTask(appContext: AppContext, coreConfig: CoreConfig)

private const val KEY_LAST_FULL_SYNC_MS = "last_full_sync_time_ms"
private const val KEY_LAST_PARTIAL_SYNC_MS = "last_partial_sync_time_ms"