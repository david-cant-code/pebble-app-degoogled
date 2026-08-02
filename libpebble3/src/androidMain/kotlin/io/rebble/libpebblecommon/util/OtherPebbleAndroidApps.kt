package io.rebble.libpebblecommon.util;

import android.app.Application
import io.rebble.libpebblecommon.connection.OtherPebbleApp
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class OtherPebbleAndroidApps(
    private val notificationAppDao: NotificationAppRealDao,
    private val application: Application,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : OtherPebbleApps {
    private val myPackageName by lazy { application.packageName }

    override fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>> =
        notificationAppDao.allAppsFlow().map { apps ->
            apps.mapNotNull { app ->
                if (app.packageName == myPackageName) return@mapNotNull null
                if (!OTHER_PEBBLE_APPS.any { otherApp -> otherApp.pkg == app.packageName }) return@mapNotNull null
                OtherPebbleApp(pkg = app.packageName, name = app.name)
            }
        }.stateIn(libPebbleCoroutineScope, SharingStarted.Lazily, emptyList())

    companion object {
        private val OTHER_PEBBLE_APPS = setOf(
            OtherPebbleApp(pkg = "com.getpebble.android.basalt", name = "Pebble"),
            OtherPebbleApp(pkg = "io.rebble.cobble", name = "Cobble"),
            OtherPebbleApp(pkg = "nodomain.freeyourgadget.gadgetbridge", name = "Gadgetbridge"),
            // In this fork the app's own id differs from upstream's, so upstream's
            // official app is (correctly) detected as a conflicting companion app.
            OtherPebbleApp(pkg = "coredevices.coreapp", name = "Pebble (Core Devices)"),
            OtherPebbleApp(pkg = "com.matejdro.micropebble", name = "microPebble"),
        )
    }
}
