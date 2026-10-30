package coredevices.coreapp

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import androidx.annotation.RequiresApi
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.anopticlabs.gravel.pkjs.recordUdpFilterInstall
import com.anopticlabs.gravel.pkjs.webViewMajorVersion
import com.anopticlabs.gravel.socketfilter.InstallResult
import com.anopticlabs.gravel.socketfilter.UdpSocketFilter
import coredevices.ExperimentalDevices
import com.anopticlabs.gravel.app.notebookEnginesModule
import com.anopticlabs.gravel.app.notebookTabModule
import com.anopticlabs.gravel.app.notebookWatchAppModule
import com.anopticlabs.gravel.app.textModelsModule
import com.anopticlabs.gravel.network.AndroidUnmeteredWifi
import com.anopticlabs.gravel.transcription.adoptUnboundServerToken
import com.anopticlabs.gravel.transcription.settleServerOnMobileData
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.alarms.NotebookStartup
import com.anopticlabs.gravel.notebook.notebookModule
import coredevices.coreapp.di.androidDefaultModule
import coredevices.coreapp.di.apiModule
import coredevices.coreapp.di.ringStubsModule
import coredevices.coreapp.di.utilModule
import coredevices.coreapp.util.FileLogWriter
import coredevices.coreapp.util.FirebaseResidueCleanup
import coredevices.coreapp.util.initLogging
import coredevices.coreapp.util.registerBluetoothPairingDebugLogger
import coredevices.pebble.PebbleAppDelegate
import coredevices.pebble.watchModule
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import com.anopticlabs.gravel.llm.LlmEngineClient
import coredevices.whisper.WhisperEngineClient
import coredevices.whisper.runningInIsolatedProcess
import io.rebble.libpebblecommon.connection.AppContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.time.Instant
import kotlin.time.toJavaDuration

private val logger = Logger.withTag("MainApplication")

class MainApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    private val pebbleAppDelegate: PebbleAppDelegate by inject()
    private val commonAppDelegate: CommonAppDelegate by inject()
    private val experimentalDevices: ExperimentalDevices by inject()
    private val fileLogWriter: FileLogWriter by inject()
    private val coreConfigHolder: CoreConfigHolder by inject()
    private val pebbleBackgroundManager: PebbleBackgroundManager by inject()

    // Gravel: set once in attachBaseContext; null in the engines' isolated processes.
    private var udpFilterResult: InstallResult? = null

    // Gravel: a socket that exists before the filter is outside it, so nothing may precede this
    // install in the process: no WebView, no Koin start, no network client. attachBaseContext
    // runs before every content provider and library initializer (android16-release,
    // ActivityThread.handleBindApplication). The isolated engine processes get no filter.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (runningInIsolatedProcess()) return
        udpFilterResult = UdpSocketFilter.install()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Fork: the engines' isolated processes instantiate this class too,
        // and nothing below may run there; see runningInIsolatedProcess.
        if (runningInIsolatedProcess()) return
        // Fork: the engine clients need the application context to bind the
        // engine processes; attached before the DI graph exists so no engine
        // call can precede it.
        WhisperEngineClient.attach(this)
        LlmEngineClient.attach(this)
        startKoin {
            modules(
                module {
                    androidContext(this@MainApplication)
                },
                androidDefaultModule,
                ringStubsModule,
                apiModule,
                utilModule,
                watchModule,
                notebookModule,
                notebookEnginesModule,
                notebookTabModule,
                notebookWatchAppModule,
                textModelsModule,
            )
        }
        // Gravel: registered before any activity starts, so the Notebook's notification gates count every one.
        registerActivityLifecycleCallbacks(get<AppVisibility>())
        initLogging()
        logUdpFilterResult()
        // Gravel: kept across process starts; see udpFilterEnforcement.
        recordUdpFilterInstall(udpFilterResult, noBackupFilesDir)
        // Gravel: the self-hosted server's Wi-Fi-only rule; ahead of pebbleAppDelegate.init, which starts libPebble,
        // and of the Notebook's first run, which may send to the server.
        get<AndroidUnmeteredWifi>().start()
        settleServerOnMobileData(coreConfigHolder, get())
        // Gravel: ahead of the first request, which goes out without a token an earlier build stored until this has run.
        adoptUnboundServerToken(coreConfigHolder, get())
        // Gravel: a force stop drops the Notebook's alarms; the sweep re-arms them from the stores.
        get<NotebookStartup>().start()
        // Fork: installs upgraded from pre-strip builds still hold the
        // Firebase SDKs' persisted refresh token and Firestore cache, and the
        // strip removed every code path that could clear them; see
        // FirebaseResidueCleanup for the threat model.
        FirebaseResidueCleanup.launchInBackground(this)
        logger.i { "onCreate() version = $appVersionName ($appVersionCode) ${if (isDebuggableBuild) "debug" else "release"}" }
        dumpPreviousExitInfo()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                logger.i { "Power state changed: isPowerSaveMode=${powerManager.isPowerSaveMode}" }
            }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        // Fork: upstream registers this pairing diagnostic unconditionally,
        // but it records every Bluetooth pairing on the phone into the
        // persisted log that bug reports upload. Debuggable builds only here;
        // the receiver additionally redacts the passkey extra as a second,
        // independent layer.
        if (isDebuggableBuild) {
            registerBluetoothPairingDebugLogger(this)
        }
        setupExceptionHandler()
        experimentalDevices.appInit()
        pebbleAppDelegate.init()
        configureStrictMode()
        scheduleBackgroundJob(AppContext(this), coreConfigHolder.config.value)
        commonAppDelegate.init()
        pebbleBackgroundManager.monitorToStartBackground()
    }

    // The install runs before logging exists, so its result is written here, into the log
    // that bug reports carry.
    private fun logUdpFilterResult() {
        val line = "UDP socket filter: $udpFilterResult abis=${Build.SUPPORTED_ABIS.joinToString()} " +
            "sdk=${Build.VERSION.SDK_INT} kernel=${System.getProperty("os.version")} webview=${webViewMajorVersion()}"
        if (udpFilterResult == InstallResult.Installed) logger.i { line } else logger.w { line }
    }

    private fun dumpPreviousExitInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am =
                getSystemService(ActivityManager::class.java)
            val reasons =
                am.getHistoricalProcessExitReasons(packageName, 0, 5)
            reasons.firstOrNull()?.let { info ->
                val time = Instant.fromEpochMilliseconds(info.timestamp)
                logger.i {
                    "Previous exit @ $time reason=${reasonName(info.reason)} " +
                            "description=${info.description} importance=${info.importance} " +
                            "pss=${info.pss} rss=${info.rss} status=${info.status}"
                }
            }
        }
    }

    @RequiresApi(30)
    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "OOM"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
            -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
            "DEPENDENCY_DIED"
        else -> "OTHER($reason)"
    }

    private fun configureStrictMode() {
        if (isDebuggableBuild) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    // .penaltyDeath() // Crash the app on violation (useful for actively debugging)
                    // .penaltyDialog() // Show a dialog (can be intrusive)
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    // .penaltyDeath()
                    .build()
            )
        }
    }

    // Fork: the platform delivers these to every process of the app, the
    // engines' isolated processes included, where onCreate returned
    // before the DI graph was built; an injected member resolved there
    // throws, and that process has no file to write anyway.
    override fun onLowMemory() {
        super.onLowMemory()
        if (runningInIsolatedProcess()) return
        fileLogWriter.logBlockingAndFlush(Severity.Info, "onLowMemory", "MainApplication", null)
    }

    override fun onTerminate() {
        super.onTerminate()
        if (runningInIsolatedProcess()) return
        fileLogWriter.logBlockingAndFlush(Severity.Info, "onTerminate", "MainApplication", null)
    }

    private fun setupExceptionHandler() {
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            fileLogWriter.logBlockingAndFlush(
                Severity.Error,
                "Unhandled exception in thread ${thread.name}: ${throwable.message}",
                "MainApplication",
                throwable
            )
            // Chain to the pre-existing handler (platform default) so the process still dies normally
            existingHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .components {
                add(SvgDecoder.Factory())
                // Trying to see if not using AnimatedImageDecoder fixes memory leaks
//                if (SDK_INT >= 28) {
//                    add(AnimatedImageDecoder.Factory())
//                } else {
                    add(GifDecoder.Factory())
//                }
            }
            .build()
    }
}

fun scheduleBackgroundJob(appContext: AppContext, coreConfig: CoreConfig) {
    logger.d { "scheduleBackgroundJob for ${coreConfig.weatherSyncInterval}" }
    val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
        repeatInterval = coreConfig.weatherSyncInterval.toJavaDuration(),
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    ).build()
    try {
        WorkManager.getInstance(appContext.context).enqueueUniquePeriodicWork(
            uniqueWorkName = "core_refresh",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            request = workRequest,
        )
    } catch (e: Throwable) {
        // Broken OEM builds throw NoSuchMethodError from WorkManager init; sync just won't run.
        logger.e(e) { "Failed to schedule background job" }
    }
}