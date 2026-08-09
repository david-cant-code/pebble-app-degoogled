package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Fork: per-app, tri-state permission model for the phone-side capabilities the
 * app grants to third-party watchapps/watchfaces (their PebbleKit JS running in a
 * WebView on the phone).
 *
 * [FollowGlobal] is the absence of an explicit choice: the app inherits the global
 * default from [io.rebble.libpebblecommon.WatchConfig]. [Allow]/[Deny] are explicit
 * per-app overrides that win over the global default in either direction. This is
 * modelled in storage as the presence/absence of a [LockerAppPermission] row:
 *  - no row            -> FollowGlobal
 *  - row(granted=true) -> Allow
 *  - row(granted=false)-> Deny
 * so "reset to default" is a row delete, not a third stored state.
 */
enum class PermissionSetting {
    FollowGlobal,
    Allow,
    Deny,
}

/**
 * Read/observe/set the tri-state per-app permissions, and resolve them against the
 * global defaults into a single effective grant. This is the one place that owns the
 * FollowGlobal-vs-default resolution so every enforcement site (geolocation bridge,
 * WebView network gate, phone-side interceptor) and the UI agree on the answer.
 */
interface WatchappPermissions {
    /** The stored tri-state choice for one app + capability. */
    fun watchappPermissionSetting(uuid: Uuid, type: LockerAppPermissionType): Flow<PermissionSetting>

    /** The resolved effective grant (tri-state collapsed against the global default). */
    fun watchappPermissionGranted(uuid: Uuid, type: LockerAppPermissionType): Flow<Boolean>

    /** One-shot resolved grant, for enforcement sites that only need a snapshot. */
    suspend fun isWatchappPermissionGranted(uuid: Uuid, type: LockerAppPermissionType): Boolean

    /** Current global default for a capability (the value FollowGlobal apps inherit). */
    fun globalDefault(type: LockerAppPermissionType): Boolean

    suspend fun setWatchappPermission(
        uuid: Uuid,
        type: LockerAppPermissionType,
        setting: PermissionSetting,
    )
}

class WatchappPermissionResolver(
    private val permissionDao: LockerAppPermissionDao,
    private val configFlow: LibPebbleConfigFlow,
) : WatchappPermissions {

    override fun globalDefault(type: LockerAppPermissionType): Boolean =
        configFlow.value.watchConfig.globalDefaultFor(type)

    override fun watchappPermissionSetting(
        uuid: Uuid,
        type: LockerAppPermissionType,
    ): Flow<PermissionSetting> =
        permissionDao.getByAppUuidAndPermissionFlow(uuid, type).map { it.toSetting() }

    override fun watchappPermissionGranted(
        uuid: Uuid,
        type: LockerAppPermissionType,
    ): Flow<Boolean> =
        // Recomputes when either the per-app row or the global default changes, so a
        // live global-default toggle immediately re-resolves every FollowGlobal app.
        combine(
            permissionDao.getByAppUuidAndPermissionFlow(uuid, type),
            configFlow.flow,
        ) { row, config ->
            row?.granted ?: config.watchConfig.globalDefaultFor(type)
        }

    override suspend fun isWatchappPermissionGranted(
        uuid: Uuid,
        type: LockerAppPermissionType,
    ): Boolean =
        permissionDao.getByAppUuidAndPermission(uuid, type)?.granted ?: globalDefault(type)

    override suspend fun setWatchappPermission(
        uuid: Uuid,
        type: LockerAppPermissionType,
        setting: PermissionSetting,
    ) {
        when (setting) {
            PermissionSetting.FollowGlobal ->
                permissionDao.deleteByAppUuidAndPermission(uuid, type)
            PermissionSetting.Allow ->
                permissionDao.insertOrReplace(LockerAppPermission(uuid, type, granted = true))
            PermissionSetting.Deny ->
                permissionDao.insertOrReplace(LockerAppPermission(uuid, type, granted = false))
        }
    }
}

private fun LockerAppPermission?.toSetting(): PermissionSetting = when {
    this == null -> PermissionSetting.FollowGlobal
    granted -> PermissionSetting.Allow
    else -> PermissionSetting.Deny
}

private fun io.rebble.libpebblecommon.WatchConfig.globalDefaultFor(
    type: LockerAppPermissionType,
): Boolean = when (type) {
    LockerAppPermissionType.Network -> watchappDefaultNetworkAllowed
    LockerAppPermissionType.Location -> watchappDefaultLocationAllowed
}
