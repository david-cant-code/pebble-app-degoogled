package coredevices.util

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import coredevices.util.models.CactusSTTMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class CoreConfigHolder(
    private val defaultValue: CoreConfig,
    private val settings: Settings,
    private val json: Json,
) {
    private fun defaultValue(): CoreConfig {
        return loadFromStorage() ?: defaultValue.also { saveToStorage(it) }
    }

    private fun migrateCactusSettings(oldConfig: CoreConfig): CoreConfig {
        val mode = settings.getIntOrNull("cactus_mode")
        val model = settings.getStringOrNull("cactus_stt_model")
        if (mode != null) {
            Logger.i("CoreConfigHolder") { "Migrating old Cactus STT settings: mode=$mode, model=$model" }
            settings.remove("cactus_mode")
            settings.remove("cactus_stt_model")
            return oldConfig.copy(
                sttConfig = STTConfig(
                    mode = CactusSTTMode.fromId(mode),
                    modelName = model,
                )
            )
        } else {
            return oldConfig
        }
    }

    private fun loadFromStorage(): CoreConfig? = settings.getStringOrNull(SETTINGS_KEY)?.let { string ->
        try {
            migrateCactusSettings(json.decodeFromString(string))
        } catch (e: SerializationException) {
            Logger.w("Error loading settings", e)
            null
        }
    }

    private fun saveToStorage(value: CoreConfig) {
        settings.set(SETTINGS_KEY, json.encodeToString(value))
    }

    fun update(value: CoreConfig) {
        saveToStorage(value)
        _config.value = value
    }

    private val _config: MutableStateFlow<CoreConfig> = MutableStateFlow(defaultValue())
    val config: StateFlow<CoreConfig> = _config.asStateFlow()
}

class CoreConfigFlow(val flow: StateFlow<CoreConfig>) {
    val value get() = flow.value
}

private const val SETTINGS_KEY = "coreapp.config"

enum class WeatherUnit(val code: String, val displayName: String) {
    Metric("m", "Metric"),
    Imperial("e", "Imperial"),
    UkHybrid("h", "Mixed (UK)"),
}

@Serializable
data class CoreConfig(
    val ignoreOtherPebbleApps: Boolean = false,
    val disableCompanionDeviceManager: Boolean = false,
    val weatherPinsV2: Boolean = true,
    val fetchWeather: Boolean = true,
    val disableFirmwareUpdateNotifications: Boolean = false,
    // Fork: opts Core watches into the Early PebbleOS release channel
    // (newest main-line tag, Core's internal-tester tier) instead of the
    // soaked default; mapped to FirmwareUpdateChannel in :pebble.
    val firmwareUpdatesEarlyChannel: Boolean = false,
    val enableIndex: Boolean = false,
    val indexPermissionsConfirmed: Boolean = false,
    val weatherUnits: WeatherUnit? = null,
    val showAllSettingsTab: Boolean = false,
    val sttConfig: STTConfig = STTConfig(),
    val interceptPKJSWeather: Boolean = true,
    val regularSyncInterval: Duration = 6.hours,
    val weatherSyncInterval: Duration = 1.hours,
    val preferHealthTab: Boolean = true,
    val obfuscateSensitiveLogs: Boolean = true,
    val hidePermissionWarningBadges: Boolean = false,
    val androidForegroundServiceForWatchConnectionV2: Boolean = true,
    val showWatchConnectionDebugInfo: Boolean = false,
    val notifyWatchFullyCharged: Boolean = true,
    // Fork: upstream defaults this to true; the fork keeps it false. The
    // eng-dash route also needs a bug-endpoint build value fork builds never
    // set, so the setting is inert here either way, and a true default would
    // show the inert "Use Core OTA service" debug toggle as switched on.
    val useEngDashOta: Boolean = false,
    /**
     * Fork: highest "What's New" changelog revision the user has already seen. Compared
     * against WHATS_NEW_VERSION to decide whether to show the one-time update dialog.
     * Defaults to 0 so an existing install upgrading into this build (whose stored config
     * predates the field) is shown the current entries once; fresh installs stamp it to
     * the current version at the end of onboarding so they are not shown what they just
     * chose during setup.
     */
    val lastSeenWhatsNewVersion: Int = 0,
) {
    /** Null until the user picks explicitly; the settings [Json] omits defaults, so a
     * locale-derived default here would never be persisted. */
    val resolvedWeatherUnits: WeatherUnit get() = weatherUnits ?: deviceDefaultWeatherUnit()
}

@Serializable
data class STTConfig(
    // Fork: LocalOnly, not upstream's RemoteOnly. The Core cloud modes need
    // a Core-account sign-in this build removed, so upstream's default can
    // never work here and the settings dropdown no longer offers it.
    val mode: CactusSTTMode = CactusSTTMode.LocalOnly,
    val modelName: String? = null,
    /** ISO 639-1 language code. Null means auto-detect. */
    val spokenLanguage: String? = null,
    /**
     * Fork, debug builds only: run the engine on one thread so a fast
     * phone reproduces a decode that overruns the watch's dictation
     * window. Honoured only when [coredevices.util.isDebugBuild] is true.
     */
    val debugSingleThread: Boolean = false,
    /**
     * Fork, debug builds only: write each dictation's engine input as a
     * WAV under the app's private files so degraded captures can be
     * replayed through the engine. Honoured only when
     * [coredevices.util.isDebugBuild] is true.
     */
    val debugCaptureDump: Boolean = false,
    /**
     * Fork, debug builds only: replace the watch's audio with the bundled
     * test clip, so an emulated watch (whose microphone is silence) or a
     * silent room still produces a real transcript. Honoured only when
     * [coredevices.util.isDebugBuild] is true.
     */
    val debugSubstituteAudio: Boolean = false,
    /**
     * Fork, debug builds only: hold each dictation's result for a fixed
     * extra delay after the decode, so the watch's deadline report runs
     * deterministically on any phone. Honoured only when
     * [coredevices.util.isDebugBuild] is true.
     */
    val debugSlowDecode: Boolean = false,
    /**
     * Fork: a self-hosted transcription server, the remote backend that
     * stands in for the removed cloud one. The full https URL of the
     * endpoint (whisper.cpp's server listens on `/inference`, OpenAI-style
     * servers on `/v1/audio/transcriptions`); null means no server. The
     * bearer token is a secret and lives in the encrypted setting, never
     * here.
     */
    val serverUrl: String? = null,
    /**
     * Model name sent with each server request. Servers that host one
     * model ignore it; OpenAI-style servers require it. Null sends none.
     */
    val serverModel: String? = null,
)