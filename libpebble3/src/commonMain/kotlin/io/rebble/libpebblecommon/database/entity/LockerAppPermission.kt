package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import kotlin.uuid.Uuid

@Entity(
    primaryKeys = ["appUuid", "permission"],
)
data class LockerAppPermission(
    val appUuid: Uuid,
    val permission: LockerAppPermissionType,
    val granted: Boolean = true,
)

// Persisted as TEXT by name (see the Room schema: `permission` column is TEXT),
// so entries may be reordered freely, but never rename an existing constant
// without a migration: a rename orphans every stored row for that permission.
enum class LockerAppPermissionType {
    Location,

    // Fork: governs whether a watchapp/watchface's phone-side PebbleKit JS may
    // reach the network at all (XHR/fetch/WebSocket, plus the phone-side weather
    // interceptors that egress on the app's behalf). Upstream only ever modelled
    // Location here and left even that ungated; Network is new to the fork's
    // watchapp-permission system.
    Network,
}