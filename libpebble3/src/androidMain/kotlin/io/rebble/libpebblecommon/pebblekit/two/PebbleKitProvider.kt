package io.rebble.libpebblecommon.pebblekit.two

import android.database.Cursor
import android.net.Uri
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.pebblekit2.PebbleKitProviderContract.ActiveApp
import io.rebble.pebblekit2.PebbleKitProviderContract.ConnectedWatch
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleKitProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class PebbleKitProvider : BasePebbleKitProvider(), LibPebbleKoinComponent {
   private lateinit var watchManager: Watches
   private lateinit var locker: LockerApi
   override lateinit var coroutineScope: LibPebbleCoroutineScope

   init {
      instance = this
   }

   override fun initialize() {
      watchManager = getKoin().get<LibPebble>()
      locker = getKoin().get<LibPebble>()
      coroutineScope = getKoin().get()

      super.initialize()
   }

   /**
    * The provider is exported, so every read is gated on the caller being a companion of an
    * installed watchapp. Without this any app on the device could read watch state, including
    * the watch identifier, with no permission and no user-visible prompt.
    *
    * Denial returns null rather than an empty cursor because that is already the outcome for an
    * unrecognised URI, so clients handle it. Failing closed while the registry is still loading
    * costs a legitimate companion one empty result at cold start; the alternative would leak
    * watch state during exactly the window before authorization is known.
    */
   override fun query(
      uri: Uri,
      projection: Array<out String?>?,
      selection: String?,
      selectionArgs: Array<out String?>?,
      sortOrder: String?
   ): Cursor? {
      val caller = callingPackage
      val registry = runCatching { getKoin().getOrNull<PebbleKitCompanionRegistry>() }.getOrNull()
      if (registry?.isAuthorized(caller) != true) {
         logger.d { "Denied PebbleKit query from $caller" }
         return null
      }
      return super.query(uri, projection, selection, selectionArgs, sortOrder)
   }

   override fun getConnectedWatches(): Flow<List<Map<String, Any?>>> {
      return watchManager.watches.map { watches ->
         watches.filterIsInstance<ConnectedPebbleDevice>()
            .map { watch ->
               val watchInfo = watch.watchInfo
               val runningFwVersion = watchInfo.runningFwVersion

               mapOf(
                  ConnectedWatch.ID to watchInfo.serial,
                  ConnectedWatch.NAME to watch.displayName(),
                  ConnectedWatch.PLATFORM to watchInfo.platform.watchType.codename,
                  ConnectedWatch.REVISION to watchInfo.platform.revision,
                  ConnectedWatch.FIRMWARE_VERSION_MAJOR to runningFwVersion.major,
                  ConnectedWatch.FIRMWARE_VERSION_MINOR to runningFwVersion.minor,
                  ConnectedWatch.FIRMWARE_VERSION_PATCH to runningFwVersion.patch,
                  ConnectedWatch.FIRMWARE_VERSION_TAG to runningFwVersion.suffix
               )
            }
      }
   }

   override fun getActiveApp(watch: WatchIdentifier): Flow<Map<String, Any?>?> {
      return watchManager.watches.flatMapLatest { watches ->
         val targetWatch = watches.filterIsInstance<ConnectedPebbleDevice>().firstOrNull { it.watchInfo.serial == watch.value }
         if (targetWatch == null) {
            return@flatMapLatest flowOf(null)
         }

         targetWatch.runningApp.flatMapLatest { appId ->
            if (appId != null) {
               locker.getLockerApp(appId).map { lockerEntry ->
                  mapOf(
                     ActiveApp.ID to appId,
                     ActiveApp.NAME to lockerEntry?.properties?.title,
                     ActiveApp.TYPE to when (lockerEntry?.properties?.type) {
                        AppType.Watchface -> ActiveApp.TYPE_VALUE_WATCHFACE
                        AppType.Watchapp -> ActiveApp.TYPE_VALUE_WATCHAPP
                        null -> ActiveApp.TYPE_VALUE_UNKNOWN
                     },
                  )
               }
            } else {
               flowOf(null)
            }
         }
      }
   }

   companion object {
      var instance: PebbleKitProvider? = null

      private val logger = Logger.withTag("PebbleKitProvider")
   }
}
