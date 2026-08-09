package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.database.dao.FakeLockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Covers the security-critical decision logic: deny-by-default, per-app overrides winning
 * over the global default in both directions, FollowGlobal being represented as the
 * absence of a stored row, each capability mapping to its own global default, and the
 * resolved-grant flow re-emitting on live default/row changes (which the running-app
 * enforcement collectors depend on). Every enforcement site (geolocation bridge, WebView
 * network gate, phone-side interceptor) resolves through this, so this is where the
 * "network/location off by default" guarantee is actually proven.
 */
class WatchappPermissionResolverTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val other = Uuid.parse("00000000-0000-0000-0000-0000000000bb")

    /** The config flow is exposed so tests can flip a global default mid-collection. */
    private class Fixture(
        val resolver: WatchappPermissionResolver,
        val dao: FakeLockerAppPermissionDao,
        val config: MutableStateFlow<LibPebbleConfig>,
    )

    private fun fixture(
        networkDefault: Boolean = false,
        locationDefault: Boolean = false,
    ): Fixture {
        val dao = FakeLockerAppPermissionDao()
        val config = MutableStateFlow(
            LibPebbleConfig(
                watchConfig = WatchConfig(
                    watchappDefaultNetworkAllowed = networkDefault,
                    watchappDefaultLocationAllowed = locationDefault,
                ),
            ),
        )
        return Fixture(WatchappPermissionResolver(dao, LibPebbleConfigFlow(config)), dao, config)
    }

    @Test
    fun shippedDefaultsDenyBothCapabilities() {
        // The whole feature hinges on this: an untouched install must deny.
        val defaults = WatchConfig()
        assertFalse(defaults.watchappDefaultNetworkAllowed, "network must default deny")
        assertFalse(defaults.watchappDefaultLocationAllowed, "location must default deny")
    }

    @Test
    fun noRowFollowsGlobalDefault() = runTest {
        val deny = fixture(networkDefault = false, locationDefault = false)
        assertFalse(deny.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        assertFalse(deny.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))

        val allow = fixture(networkDefault = true, locationDefault = true)
        assertTrue(allow.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        assertTrue(allow.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))
    }

    @Test
    fun asymmetricDefaultsResolvePerCapability() = runTest {
        // Symmetric defaults cannot tell the two capability-to-config-field mappings
        // apart, so a cross-wire (Location reading the network default, or the
        // reverse) would pass every symmetric test while granting the wrong
        // capability globally. Pin each direction with the defaults split.
        val networkOnly = fixture(networkDefault = true, locationDefault = false)
        assertTrue(networkOnly.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        assertFalse(
            networkOnly.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location),
            "location must not inherit the network default",
        )

        val locationOnly = fixture(networkDefault = false, locationDefault = true)
        assertFalse(
            locationOnly.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network),
            "network must not inherit the location default",
        )
        assertTrue(locationOnly.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))
    }

    @Test
    fun globalDefaultFlowTracksItsOwnCapability() = runTest {
        val f = fixture(networkDefault = true, locationDefault = false)
        assertTrue(f.resolver.globalDefault(LockerAppPermissionType.Network).first())
        assertFalse(f.resolver.globalDefault(LockerAppPermissionType.Location).first())

        // Flipping one default must be visible on that capability's flow only.
        f.config.value = f.config.value.copy(
            watchConfig = f.config.value.watchConfig.copy(watchappDefaultLocationAllowed = true),
        )
        assertTrue(f.resolver.globalDefault(LockerAppPermissionType.Location).first())
        assertTrue(f.resolver.globalDefault(LockerAppPermissionType.Network).first())
    }

    @Test
    fun perAppAllowOverridesDenyGlobal() = runTest {
        val f = fixture(networkDefault = false)
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertTrue(f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        // A different app is unaffected and still follows the deny default.
        assertFalse(f.resolver.isWatchappPermissionGranted(other, LockerAppPermissionType.Network))
    }

    @Test
    fun perAppDenyOverridesAllowGlobal() = runTest {
        val f = fixture(networkDefault = true)
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertFalse(f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
    }

    @Test
    fun followGlobalDeletesRowAndReinheritsDefault() = runTest {
        val f = fixture(networkDefault = true)
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertFalse(f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))

        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.FollowGlobal)
        assertNull(
            f.dao.getByAppUuidAndPermission(uuid, LockerAppPermissionType.Network),
            "FollowGlobal must remove the row, not store a third state",
        )
        // Now re-inherits the (allow) global default.
        assertTrue(f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
    }

    @Test
    fun networkAndLocationAreIndependent() = runTest {
        val f = fixture(networkDefault = false, locationDefault = false)
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Allow)
        assertTrue(f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))
        assertFalse(
            f.resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network),
            "granting Location must not grant Network",
        )
    }

    @Test
    fun settingFlowReflectsStoredTriState() = runTest {
        val f = fixture()
        assertEquals(
            PermissionSetting.FollowGlobal,
            f.resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertEquals(
            PermissionSetting.Allow,
            f.resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertEquals(
            PermissionSetting.Deny,
            f.resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
    }

    @Test
    fun grantedFlowResolvesRowThenDefault() = runTest {
        val f = fixture(networkDefault = false)
        // No row -> follows deny default.
        assertFalse(f.resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network).first())
        // Explicit allow -> granted.
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertTrue(f.resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network).first())
    }

    @Test
    fun grantedFlowReEmitsOnLiveGlobalDefaultChange() = runTest {
        // The running-app enforcement (the WebView network collector) keeps a
        // collection of this flow open for the whole session and relies on a
        // global-default toggle producing a fresh emission; a resolver that read the
        // default once per collection would pass every one-shot test in this suite
        // and silently break live enforcement.
        val f = fixture(networkDefault = false)
        val emissions = mutableListOf<Boolean>()
        backgroundScope.launch {
            f.resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
                .collect { emissions += it }
        }
        runCurrent()
        assertEquals(listOf(false), emissions)

        f.config.value = f.config.value.copy(
            watchConfig = f.config.value.watchConfig.copy(watchappDefaultNetworkAllowed = true),
        )
        runCurrent()
        assertEquals(listOf(false, true), emissions, "a live default flip must re-resolve FollowGlobal apps")
    }

    @Test
    fun grantedFlowReEmitsOnLivePerAppRowChange() = runTest {
        val f = fixture(networkDefault = true)
        val emissions = mutableListOf<Boolean>()
        backgroundScope.launch {
            f.resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
                .collect { emissions += it }
        }
        runCurrent()
        assertEquals(listOf(true), emissions)

        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        runCurrent()
        assertEquals(listOf(true, false), emissions, "a live per-app deny must reach open collections")
    }
}
