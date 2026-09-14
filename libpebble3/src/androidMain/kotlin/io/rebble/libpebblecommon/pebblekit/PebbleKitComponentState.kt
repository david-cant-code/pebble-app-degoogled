package io.rebble.libpebblecommon.pebblekit

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val logger = Logger.withTag("PebbleKitComponentState")

data class PebbleKitToggles(val classic: Boolean, val pebbleKit2: Boolean)

fun WatchConfig.pebbleKitToggles() =
    PebbleKitToggles(classic = classicPebbleKitEnabled, pebbleKit2 = pebbleKit2Enabled)

internal fun PebbleKitToggles.enables(surface: PebbleKitSurface): Boolean = when (surface) {
    PebbleKitSurface.Classic -> classic
    PebbleKitSurface.PebbleKit2 -> pebbleKit2
}

/**
 * Fork: disables the manifest-declared PebbleKit components while their toggle is off.
 *
 * Platform behaviour relied on, android16-release:
 * - ActiveServices.retrieveServiceLocked, ContentProviderHelper.getContentProviderImpl: resolved
 *   without MATCH_DISABLED_COMPONENTS, so a bind or acquisition of a disabled component fails.
 * - PackageManagerService.setEnabledSettings: DONT_KILL_APP defers the package-changed broadcast
 *   by about a second (ten just after boot); until it lands a held provider and a live service
 *   record keep working, which the in-code gates cover.
 * - ContentProviderHelper.removeDyingProviderLocked, reached while that broadcast is handled:
 *   unpublishing the provider kills a foreign, non-persistent process that still holds a stable
 *   connection to it then.
 * - ActiveServices.bringDownDisabledPackageServicesLocked, bringDownServiceLocked: disabling the
 *   sender service tears it down and reports the loss to every bound client.
 * - ComponentResolver.assertProvidersNotDefined: a disabled provider keeps its authority reserved.
 * - PackageManagerService.resetComponentEnabledSettingsIfNeededLPw: the state survives clear-data,
 *   as this manifest does not set resetEnabledSettingsOnAppDataCleared.
 */
class PebbleKitComponentState(
    private val setComponentEnabled: (className: String, enabled: Boolean) -> Unit,
    private val watchConfig: WatchConfigFlow,
    private val scope: CoroutineScope,
) {
    // Per component, the state its last call set; a component whose last call threw has no
    // entry, so it matches no desired state and the next config emission applies it again.
    @Volatile
    private var committed: Map<String, Boolean> = emptyMap()

    private val applied = MutableStateFlow(PebbleKitToggles(classic = false, pebbleKit2 = false))

    /**
     * Whether every component of [surface] was enabled as of the last apply that returned; the
     * config changes before this, and so do the calls inside an apply that is still running.
     */
    fun appliedEnabled(surface: PebbleKitSurface): Flow<Boolean> = applied.map { it.enables(surface) }

    /** Applies the current toggles on the caller's thread, then follows every later change. */
    fun init() {
        apply(watchConfig.value.pebbleKitToggles())
        scope.launch {
            watchConfig.flow.map { it.watchConfig.pebbleKitToggles() }.collect { desired ->
                if (desiredStates(desired) != committed) apply(desired)
            }
        }
    }

    fun apply(desired: PebbleKitToggles) {
        committed = desiredStates(desired).filter { (className, enabled) ->
            try {
                setComponentEnabled(className, enabled)
                true
            } catch (e: Exception) {
                logger.e(e) { "Failed to set $className to ${if (enabled) "default" else "disabled"}" }
                false
            }
        }
        applied.value = PebbleKitToggles(
            classic = allEnabled(PebbleKitSurface.Classic),
            pebbleKit2 = allEnabled(PebbleKitSurface.PebbleKit2),
        )
        logger.d { "Applied PebbleKit component state: ${applied.value} for $desired" }
    }

    private fun allEnabled(surface: PebbleKitSurface): Boolean =
        surfaceComponents.getValue(surface).all { committed[it] == true }

    companion object {
        // Literal names: the pebblekit2 classes need the Java 21 runtime that library targets,
        // which the host suite may not have. PebbleKitComponentManifestTest pins them.
        private const val CLASSIC_PROVIDER = "io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider"
        private const val PEBBLEKIT2_PROVIDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider"
        private const val PEBBLEKIT2_SENDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver"

        private val surfaceComponents: Map<PebbleKitSurface, List<String>> = mapOf(
            PebbleKitSurface.Classic to listOf(CLASSIC_PROVIDER),
            PebbleKitSurface.PebbleKit2 to listOf(PEBBLEKIT2_PROVIDER, PEBBLEKIT2_SENDER),
        )

        internal fun desiredStates(toggles: PebbleKitToggles): Map<String, Boolean> =
            surfaceComponents.flatMap { (surface, classNames) -> classNames.map { it to toggles.enables(surface) } }.toMap()

        fun create(
            context: Context,
            watchConfig: WatchConfigFlow,
            scope: LibPebbleCoroutineScope,
        ): PebbleKitComponentState {
            val packageManager = context.packageManager
            val packageName = context.packageName
            return PebbleKitComponentState(
                setComponentEnabled = { className, enabled ->
                    // On restores the manifest default; the runtime only ever adds the off override.
                    val state = if (enabled) COMPONENT_ENABLED_STATE_DEFAULT else COMPONENT_ENABLED_STATE_DISABLED
                    packageManager.setComponentEnabledSetting(ComponentName(packageName, className), state, DONT_KILL_APP)
                },
                watchConfig = watchConfig,
                scope = scope,
            )
        }
    }
}
