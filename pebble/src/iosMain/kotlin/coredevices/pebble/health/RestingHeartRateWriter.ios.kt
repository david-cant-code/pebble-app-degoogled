package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.AppContext

// Mechanical actual so the iOS target keeps compiling; iOS is unmaintained in this fork.
internal actual fun createRestingHeartRateWriter(appContext: AppContext): RestingHeartRateWriter =
    object : RestingHeartRateWriter {
        override fun isSupported() = false
        override suspend fun isGranted() = false
        override suspend fun requestPermission() = false
        override suspend fun write(entries: List<RestingHeartRateEntry>) = Result.success(Unit)
    }
