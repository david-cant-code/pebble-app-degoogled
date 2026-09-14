package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.pebblekit.PebbleKitSurface
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitClassic
import io.rebble.libpebblecommon.pebblekit.pebbleKitSurface
import io.rebble.libpebblecommon.pebblekit.two.PebbleKit2

actual fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo,
    pkjsRunning: Boolean,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
    connectionCoroutineScope: ConnectionCoroutineScope,
): CompanionApp? {
    return when (appInfo.pebbleKitSurface()) {
        PebbleKitSurface.PebbleKit2 ->
            PebbleKit2(device, appInfo, pkjsRunning, libPebbleCoroutineScope, connectionCoroutineScope)
        PebbleKitSurface.Classic ->
            PebbleKitClassic(device, appInfo, connectionCoroutineScope, libPebbleKoin().get<WatchConfigFlow>())
    }
}

private fun libPebbleKoin() = object : LibPebbleKoinComponent {}.getKoin()
