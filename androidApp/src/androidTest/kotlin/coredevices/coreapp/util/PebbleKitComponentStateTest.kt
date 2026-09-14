package coredevices.coreapp.util

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.pebblekit.PebbleKitComponentState
import io.rebble.libpebblecommon.pebblekit.pebbleKitToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val CLASSIC_PROVIDER = "io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider"
private const val PK2_PROVIDER = "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider"
private const val PK2_SERVICE = "io.rebble.libpebblecommon.pebblekit.two.PebbleSenderReceiver"
private const val BASALT_AUTHORITY = "com.getpebble.android.provider.basalt"

/**
 * Drives [PebbleKitComponentState] against the real PackageManager: the enabled state of the
 * three manifest components follows the toggles, a disabled provider no longer resolves for a
 * client while its authority stays declared, and the collector follows a config flow. The
 * device's real state is reapplied from the app's config afterwards.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.PebbleKitComponentStateTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class PebbleKitComponentStateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager
    private val libPebble: LibPebble = GlobalContext.get().get()
    private lateinit var original: LibPebbleConfig
    private var scope: CoroutineScope? = null

    @Before
    fun snapshotConfig() {
        original = libPebble.config.value
    }

    @After
    fun restore() {
        scope?.cancel()
        scope = null
        // The app's own collector only reacts to config changes, so put the components back
        // to what the app's config says rather than leaving the last test value behind.
        PebbleKitComponentState.create(
            context,
            WatchConfigFlow(MutableStateFlow(original)),
            LibPebbleCoroutineScope(Dispatchers.Default),
        ).apply(original.watchConfig.pebbleKitToggles())
    }

    @Test
    fun componentsFollowTheToggles() {
        val config = MutableStateFlow(original)
        val state = stateFor(config)

        state.apply(toggles(classic = false, pebbleKit2 = true))
        assertTrue(isDisabled(CLASSIC_PROVIDER), "classic provider not disabled")
        assertTrue(!isDisabled(PK2_PROVIDER), "PebbleKit 2 provider disabled")
        assertTrue(!isDisabled(PK2_SERVICE), "PebbleKit 2 service disabled")

        state.apply(toggles(classic = true, pebbleKit2 = false))
        assertTrue(!isDisabled(CLASSIC_PROVIDER), "classic provider still disabled")
        assertTrue(isDisabled(PK2_PROVIDER), "PebbleKit 2 provider not disabled")
        assertTrue(isDisabled(PK2_SERVICE), "PebbleKit 2 service not disabled")
    }

    @Test
    fun aDisabledProviderStopsResolvingButKeepsItsAuthority() {
        val state = stateFor(MutableStateFlow(original))

        state.apply(toggles(classic = false, pebbleKit2 = true))
        assertNull(resolveBasalt(matchDisabled = false), "the disabled basalt provider still resolves")
        val declared = resolveBasalt(matchDisabled = true)
        assertNotNull(declared, "the basalt authority is no longer declared while disabled")
        assertEquals(context.packageName, declared.packageName)

        state.apply(toggles(classic = true, pebbleKit2 = true))
        assertNotNull(resolveBasalt(matchDisabled = false), "the re-enabled basalt provider does not resolve")
    }

    @Test
    fun theCollectorAppliesEveryConfigChange() {
        val config = MutableStateFlow(withToggles(original, classic = true, pebbleKit2 = true))
        val state = stateFor(config)
        // Start from the opposite of the config's first value, whatever the device's persisted
        // config left behind, so the startup apply is what makes the first wait pass.
        state.apply(toggles(classic = false, pebbleKit2 = false))
        assertTrue(isDisabled(CLASSIC_PROVIDER) && isDisabled(PK2_SERVICE), "forced start state not applied")
        state.init()
        awaitDisabled(CLASSIC_PROVIDER, false)
        awaitDisabled(PK2_SERVICE, false)
        awaitDisabled(PK2_PROVIDER, false)

        config.value = withToggles(original, classic = false, pebbleKit2 = true)
        awaitDisabled(CLASSIC_PROVIDER, true)

        config.value = withToggles(original, classic = false, pebbleKit2 = false)
        awaitDisabled(PK2_SERVICE, true)
        awaitDisabled(PK2_PROVIDER, true)

        config.value = withToggles(original, classic = true, pebbleKit2 = true)
        awaitDisabled(CLASSIC_PROVIDER, false)
        awaitDisabled(PK2_SERVICE, false)
    }

    private fun stateFor(config: MutableStateFlow<LibPebbleConfig>): PebbleKitComponentState {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = testScope
        return PebbleKitComponentState.create(
            context,
            WatchConfigFlow(config),
            LibPebbleCoroutineScope(testScope.coroutineContext),
        )
    }

    private fun toggles(classic: Boolean, pebbleKit2: Boolean) =
        withToggles(original, classic, pebbleKit2).watchConfig.pebbleKitToggles()

    private fun withToggles(config: LibPebbleConfig, classic: Boolean, pebbleKit2: Boolean) =
        config.copy(
            watchConfig = config.watchConfig.copy(
                classicPebbleKitEnabled = classic,
                pebbleKit2Enabled = pebbleKit2,
            ),
        )

    private fun isDisabled(className: String): Boolean =
        packageManager.getComponentEnabledSetting(ComponentName(context.packageName, className)) ==
            COMPONENT_ENABLED_STATE_DISABLED

    private fun awaitDisabled(className: String, disabled: Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (isDisabled(className) != disabled) {
            if (System.currentTimeMillis() > deadline) {
                fail("$className did not become ${if (disabled) "disabled" else "enabled"} in time")
            }
            Thread.sleep(50)
        }
    }

    private fun resolveBasalt(matchDisabled: Boolean) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveContentProvider(
                BASALT_AUTHORITY,
                PackageManager.ComponentInfoFlags.of(
                    if (matchDisabled) PackageManager.MATCH_DISABLED_COMPONENTS.toLong() else 0L,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(
                BASALT_AUTHORITY,
                if (matchDisabled) PackageManager.MATCH_DISABLED_COMPONENTS else 0,
            )
        }
}
