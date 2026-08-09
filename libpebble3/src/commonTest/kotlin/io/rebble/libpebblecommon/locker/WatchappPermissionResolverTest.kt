package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Covers the security-critical decision logic: deny-by-default, per-app overrides winning
 * over the global default in both directions, and FollowGlobal being represented as the
 * absence of a stored row. Every enforcement site (geolocation bridge, WebView network
 * gate, phone-side interceptor) resolves through this, so this is where the "network/
 * location off by default" guarantee is actually proven.
 */
class WatchappPermissionResolverTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val other = Uuid.parse("00000000-0000-0000-0000-0000000000bb")

    private fun resolver(
        networkDefault: Boolean = false,
        locationDefault: Boolean = false,
    ): Pair<WatchappPermissionResolver, FakeLockerAppPermissionDao> {
        val dao = FakeLockerAppPermissionDao()
        val configFlow = LibPebbleConfigFlow(
            MutableStateFlow(
                LibPebbleConfig(
                    watchConfig = WatchConfig(
                        watchappDefaultNetworkAllowed = networkDefault,
                        watchappDefaultLocationAllowed = locationDefault,
                    ),
                ),
            ),
        )
        return WatchappPermissionResolver(dao, configFlow) to dao
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
        val (denyResolver, _) = resolver(networkDefault = false, locationDefault = false)
        assertFalse(denyResolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        assertFalse(denyResolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))

        val (allowResolver, _) = resolver(networkDefault = true, locationDefault = true)
        assertTrue(allowResolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        assertTrue(allowResolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))
    }

    @Test
    fun perAppAllowOverridesDenyGlobal() = runTest {
        val (resolver, _) = resolver(networkDefault = false)
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertTrue(resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
        // A different app is unaffected and still follows the deny default.
        assertFalse(resolver.isWatchappPermissionGranted(other, LockerAppPermissionType.Network))
    }

    @Test
    fun perAppDenyOverridesAllowGlobal() = runTest {
        val (resolver, _) = resolver(networkDefault = true)
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertFalse(resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
    }

    @Test
    fun followGlobalDeletesRowAndReinheritsDefault() = runTest {
        val (resolver, dao) = resolver(networkDefault = true)
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertFalse(resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))

        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.FollowGlobal)
        assertNull(
            dao.getByAppUuidAndPermission(uuid, LockerAppPermissionType.Network),
            "FollowGlobal must remove the row, not store a third state",
        )
        // Now re-inherits the (allow) global default.
        assertTrue(resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network))
    }

    @Test
    fun networkAndLocationAreIndependent() = runTest {
        val (resolver, _) = resolver(networkDefault = false, locationDefault = false)
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Allow)
        assertTrue(resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Location))
        assertFalse(
            resolver.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network),
            "granting Location must not grant Network",
        )
    }

    @Test
    fun settingFlowReflectsStoredTriState() = runTest {
        val (resolver, _) = resolver()
        assertEquals(
            PermissionSetting.FollowGlobal,
            resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertEquals(
            PermissionSetting.Allow,
            resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
        assertEquals(
            PermissionSetting.Deny,
            resolver.watchappPermissionSetting(uuid, LockerAppPermissionType.Network).first(),
        )
    }

    @Test
    fun grantedFlowResolvesRowThenDefault() = runTest {
        val (resolver, _) = resolver(networkDefault = false)
        // No row -> follows deny default.
        assertFalse(resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network).first())
        // Explicit allow -> granted.
        resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        assertTrue(resolver.watchappPermissionGranted(uuid, LockerAppPermissionType.Network).first())
    }
}

/**
 * In-memory stand-in for the Room DAO, backed by a single state flow so the reactive
 * queries used by the resolver emit on change.
 */
private class FakeLockerAppPermissionDao : LockerAppPermissionDao {
    private val rows = MutableStateFlow<Map<Pair<Uuid, LockerAppPermissionType>, LockerAppPermission>>(emptyMap())

    override suspend fun insertOrReplace(permission: LockerAppPermission) {
        rows.value = rows.value + ((permission.appUuid to permission.permission) to permission)
    }

    override suspend fun deleteByAppUuid(appUuid: Uuid) {
        rows.value = rows.value.filterKeys { it.first != appUuid }
    }

    override suspend fun deleteByAppUuidAndPermission(appUuid: Uuid, permission: LockerAppPermissionType) {
        rows.value = rows.value - (appUuid to permission)
    }

    override suspend fun getByAppUuid(appUuid: Uuid): List<LockerAppPermission> =
        rows.value.values.filter { it.appUuid == appUuid }

    override suspend fun getByAppUuidAndPermission(
        appUuid: Uuid,
        permission: LockerAppPermissionType,
    ): LockerAppPermission? = rows.value[appUuid to permission]

    override fun getByAppUuidAndPermissionFlow(
        appUuid: Uuid,
        permission: LockerAppPermissionType,
    ): Flow<LockerAppPermission?> = rows.map { it[appUuid to permission] }
}
