package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthManager

// iOS keeps health-kmp's own HealthKit flow; iOS is unmaintained in this fork.
internal actual suspend fun requestHealthPermissionsWithDialog(healthManager: HealthManager) {
    healthManager.requestAuthorization(
        readTypes = PlatformHealthSync.RequestedReadTypes,
        writeTypes = PlatformHealthSync.RequestedWriteTypes,
    )
}
