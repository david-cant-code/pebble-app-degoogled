package io.rebble.libpebblecommon.pebblekit.two

import android.database.Cursor
import android.database.MatrixCursor
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
      val caller = callingPackage ?: return null
      val registry = runCatching { getKoin().getOrNull<PebbleKitCompanionRegistry>() }.getOrNull()
      if (registry?.isAuthorized(caller) != true) {
         logger.d { "Denied PebbleKit query from $caller" }
         return null
      }
      val identity = runCatching { getKoin().getOrNull<PebbleKitWatchIdentity>() }.getOrNull()
         ?: return null

      return when (uri.pathSegments.firstOrNull()) {
         ConnectedWatch.CONTENT_PATH ->
            super.query(uri, projection, selection, selectionArgs, sortOrder)
               ?.let { pseudonymiseWatchIds(it, caller, identity) }

         // The watch is addressed by a path segment, and the caller only ever saw a pseudonym,
         // so translate it back before the base class tries to match it against a real serial.
         ActiveApp.CONTENT_PATH -> {
            val supplied = uri.pathSegments.getOrNull(1) ?: return null
            val serial = identity.resolveSerial(caller, supplied, connectedSerials())
               ?: return null
            val rebuilt = uri.buildUpon()
               .path(null)
               .appendPath(ActiveApp.CONTENT_PATH)
               .appendPath(serial)
               .build()
            super.query(rebuilt, projection, selection, selectionArgs, sortOrder)
         }

         else -> super.query(uri, projection, selection, selectionArgs, sortOrder)
      }
   }

   private fun connectedSerials(): List<String> =
      runCatching {
         getKoin().getOrNull<LibPebble>()?.watches?.value
            ?.filterIsInstance<ConnectedPebbleDevice>()
            ?.map { it.watchInfo.serial }
      }.getOrNull().orEmpty()

   /**
    * Rebuilds the connected-watch rows with the serial replaced by this caller's pseudonym.
    *
    * A row whose identifier cannot be derived is dropped rather than passed through, so a
    * failure in the identity layer cannot degrade into disclosing the serial it was meant to
    * replace.
    */
   private fun pseudonymiseWatchIds(
      cursor: Cursor,
      callingPackage: String,
      identity: PebbleKitWatchIdentity,
   ): Cursor {
      val columns = cursor.columnNames
      val idIndex = columns.indexOf(ConnectedWatch.ID)
      if (idIndex < 0) return cursor

      val out = MatrixCursor(columns, cursor.count)
      cursor.use { source ->
         while (source.moveToNext()) {
            val serial = source.getString(idIndex) ?: continue
            val pseudonym = identity.pseudonymFor(callingPackage, serial) ?: continue
            val row = arrayOfNulls<Any>(columns.size)
            for (index in columns.indices) {
               row[index] = if (index == idIndex) {
                  pseudonym
               } else {
                  when (source.getType(index)) {
                     Cursor.FIELD_TYPE_NULL -> null
                     Cursor.FIELD_TYPE_INTEGER -> source.getLong(index)
                     Cursor.FIELD_TYPE_FLOAT -> source.getDouble(index)
                     Cursor.FIELD_TYPE_BLOB -> source.getBlob(index)
                     else -> source.getString(index)
                  }
               }
            }
            out.addRow(row)
         }
      }
      return out
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
