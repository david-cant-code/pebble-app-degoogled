package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.TimelinePinEntity
import io.rebble.libpebblecommon.database.entity.TimelinePinSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import io.rebble.libpebblecommon.database.entity.TimelineReminderEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminderSyncEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Structural stand-ins for the timeline DAOs, for tests that need to construct
 * timeline-adjacent classes (e.g. RemoteTimelineEmulator as a dependency of
 * HttpInterceptorManager) without a Room database. Every member fails loudly:
 * these exist to satisfy constructors on code paths the test never exercises,
 * and an unexpected call means the test wandered onto a path it does not fake.
 */
class FakeTimelinePinRealDao : TimelinePinRealDao {
    override fun dirtyRecordsForWatchInsert(
        identifier: String,
        timestampMs: Long,
        insertOnlyAfterMs: Long,
    ): Flow<List<TimelinePinEntity>> = TODO("not faked")

    override fun dirtyRecordsForWatchDelete(
        identifier: String,
        timestampMs: Long,
    ): Flow<List<TimelinePinEntity>> = TODO("not faked")

    override suspend fun deleteStaleRecords(timestampMs: Long): Unit = TODO("not faked")

    override suspend fun markSyncedToWatch(syncRecord: TimelinePinSyncEntity): Unit = TODO("not faked")

    override suspend fun markDeletedFromWatch(syncRecord: TimelinePinSyncEntity): Unit = TODO("not faked")

    override fun existsOnWatch(identifier: String, primaryKey: Uuid): Flow<Boolean> = TODO("not faked")

    override suspend fun insertOrReplace(item: TimelinePinEntity): Unit = TODO("not faked")

    override suspend fun insertOrReplaceAll(items: List<TimelinePinEntity>): Unit = TODO("not faked")

    override suspend fun markForDeletion(itemId: Uuid): Unit = TODO("not faked")

    override suspend fun markAllForDeletion(itemIds: List<Uuid>): Unit = TODO("not faked")

    override suspend fun markAllDeletedFromWatch(identifier: String): Unit = TODO("not faked")

    override suspend fun deleteSyncRecordsForDevicesWhichDontExist(): Unit = TODO("not faked")

    override suspend fun getEntry(itemId: Uuid): TimelinePin? = TODO("not faked")

    override fun getEntryFlow(itemId: Uuid): Flow<TimelinePin?> = TODO("not faked")

    override suspend fun getPinsForWatchapp(parentId: Uuid): List<TimelinePin> = TODO("not faked")
}

/** See [FakeTimelinePinRealDao]. */
class FakeTimelineReminderRealDao : TimelineReminderRealDao {
    override fun dirtyRecordsForWatchInsert(
        identifier: String,
        timestampMs: Long,
        insertOnlyAfterMs: Long,
    ): Flow<List<TimelineReminderEntity>> = TODO("not faked")

    override fun dirtyRecordsForWatchDelete(
        identifier: String,
        timestampMs: Long,
    ): Flow<List<TimelineReminderEntity>> = TODO("not faked")

    override suspend fun deleteStaleRecords(timestampMs: Long): Unit = TODO("not faked")

    override suspend fun markSyncedToWatch(syncRecord: TimelineReminderSyncEntity): Unit = TODO("not faked")

    override suspend fun markDeletedFromWatch(syncRecord: TimelineReminderSyncEntity): Unit = TODO("not faked")

    override fun existsOnWatch(identifier: String, primaryKey: Uuid): Flow<Boolean> = TODO("not faked")

    override suspend fun insertOrReplace(item: TimelineReminderEntity): Unit = TODO("not faked")

    override suspend fun insertOrReplaceAll(items: List<TimelineReminderEntity>): Unit = TODO("not faked")

    override suspend fun markForDeletion(itemId: Uuid): Unit = TODO("not faked")

    override suspend fun markAllForDeletion(itemIds: List<Uuid>): Unit = TODO("not faked")

    override suspend fun markAllDeletedFromWatch(identifier: String): Unit = TODO("not faked")

    override suspend fun deleteSyncRecordsForDevicesWhichDontExist(): Unit = TODO("not faked")

    override suspend fun getEntry(itemId: Uuid): TimelineReminder? = TODO("not faked")

    override fun getEntryFlow(itemId: Uuid): Flow<TimelineReminder?> = TODO("not faked")

    override suspend fun getRemindersForPin(parentId: Uuid): List<TimelineReminder> = TODO("not faked")

    override suspend fun markForDeletionByParentId(parentId: Uuid): Unit = TODO("not faked")

    override suspend fun markForDeletionByParentIds(parentIds: List<Uuid>): Unit = TODO("not faked")
}
