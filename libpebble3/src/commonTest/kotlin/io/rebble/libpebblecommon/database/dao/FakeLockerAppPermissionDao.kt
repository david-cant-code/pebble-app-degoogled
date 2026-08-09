package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * In-memory stand-in for the Room DAO, backed by a single state flow so the reactive
 * queries used by the permission resolver emit on change. Shared by every test that
 * exercises the watchapp permission model (resolver, geolocation gate, PKJS bridge
 * gate), so they all resolve through the same storage semantics as production:
 * row absence = FollowGlobal.
 */
class FakeLockerAppPermissionDao : LockerAppPermissionDao {
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
