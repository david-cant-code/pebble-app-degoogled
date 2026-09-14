package io.rebble.libpebblecommon.pebblekit.two

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.pebblekit.PebbleKitComponentState
import io.rebble.libpebblecommon.pebblekit.toggleAllows
import io.rebble.pebblekit2.PebbleKitProviderContract
import io.rebble.pebblekit2.PebbleKitProviderContract.ActiveApp
import io.rebble.pebblekit2.PebbleKitProviderContract.ConnectedWatch
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleKitProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The `.pebblekit` ContentProvider. Fork: serves from [PebbleKit2ProviderState], never the library's `initialize()` or `query()`. */
class PebbleKitProvider : BasePebbleKitProvider(), LibPebbleKoinComponent {

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
      // Fork: read per call like everything below (see toggleAllows); the registry is resolved
      // only once the toggle admits the query.
      val decision = pebbleKitQueryDecision(
         pebbleKit2Enabled = { getKoin().getOrNull<WatchConfigFlow>()?.value?.pebbleKit2Enabled },
         caller = callingPackage,
         isAuthorized = { pkg ->
            runCatching { getKoin().getOrNull<PebbleKitCompanionRegistry>() }.getOrNull()?.isAuthorized(pkg) == true
         },
      )
      val caller = when (decision) {
         is PebbleKitQueryDecision.Refuse -> {
            logger.d { "Denied PebbleKit query from $callingPackage: ${decision.reason}" }
            return null
         }
         is PebbleKitQueryDecision.Admit -> decision.caller
      }
      val identity = runCatching { getKoin().getOrNull<PebbleKitWatchIdentity>() }.getOrNull()
         ?: return null
      val state = runCatching { getKoin().getOrNull<PebbleKit2ProviderState>() }.getOrNull()
         ?: return null

      return when (uri.pathSegments.firstOrNull()) {
         ConnectedWatch.CONTENT_PATH -> cursorOf(
            ConnectedWatch.ALL_COLUMNS,
            projection,
            pseudonymiseRows(state.connectedWatches(), ConnectedWatch.ID) { serial ->
               identity.pseudonymFor(caller, serial)
            },
         )

         // The watch is addressed by a path segment, and the caller only ever saw a pseudonym,
         // so translate it back before looking the watch up by its real serial.
         ActiveApp.CONTENT_PATH -> {
            val supplied = uri.pathSegments.getOrNull(1) ?: return null
            val serial = identity.resolveSerial(caller, supplied, state.connectedSerials())
               ?: return null
            cursorOf(ActiveApp.ALL_COLUMNS, projection, listOfNotNull(state.activeApp(serial)))
         }

         // Fail closed on paths this override does not recognise. The base class serves only
         // the two paths above today, but a library upgrade could add one that carries watch
         // data, and serving it would hand it to callers unpseudonymised with no diff here to
         // review.
         else -> null
      }
   }

   private fun cursorOf(
      allColumns: List<String>,
      projection: Array<out String?>?,
      rows: List<Map<String, Any?>>,
   ): Cursor {
      val columns = projectedColumns(projection?.toList(), allColumns)
      return MatrixCursor(columns.toTypedArray(), rows.size).apply {
         rows.forEach { addRow(rowValues(it, columns).toTypedArray()) }
      }
   }

   override fun getConnectedWatches(): Flow<List<Map<String, Any?>>> =
      connectedWatchRows(getKoin().get<LibPebble>())

   override fun getActiveApp(watch: WatchIdentifier): Flow<Map<String, Any?>?> =
      activeAppRow(getKoin().get<LibPebble>(), getKoin().get<LibPebble>(), watch.value)

   private companion object {
      val logger = Logger.withTag("PebbleKitProvider")
   }
}

/** The connected-watch rows in the library's column vocabulary, real serial under [ConnectedWatch.ID]. */
internal fun connectedWatchRows(watches: Watches): Flow<List<Map<String, Any?>>> =
   watches.watches.map { devices ->
      devices.filterIsInstance<ConnectedPebbleDevice>()
         .map { watch ->
            val watchInfo = watch.watchInfo
            val runningFwVersion = watchInfo.runningFwVersion

            mapOf(
               ConnectedWatch.ID to watchInfo.serial,
               ConnectedWatch.NAME to pebbleKitWatchName(watch.name),
               ConnectedWatch.PLATFORM to watchInfo.platform.watchType.codename,
               ConnectedWatch.REVISION to watchInfo.platform.revision,
               ConnectedWatch.FIRMWARE_VERSION_MAJOR to runningFwVersion.major,
               ConnectedWatch.FIRMWARE_VERSION_MINOR to runningFwVersion.minor,
               ConnectedWatch.FIRMWARE_VERSION_PATCH to runningFwVersion.patch,
               ConnectedWatch.FIRMWARE_VERSION_TAG to runningFwVersion.suffix
            )
         }
   }

internal fun activeAppRow(watches: Watches, locker: LockerApi, serial: String): Flow<Map<String, Any?>?> =
   watches.watches.flatMapLatest { devices ->
      val targetWatch = devices.filterIsInstance<ConnectedPebbleDevice>().firstOrNull { it.watchInfo.serial == serial }
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

internal fun createPebbleKit2ProviderState(
   context: Context,
   libPebble: LibPebble,
   watchConfig: WatchConfigFlow,
   componentState: PebbleKitComponentState,
   scope: LibPebbleCoroutineScope,
): PebbleKit2ProviderState {
   val packageName = context.packageName
   val logger = Logger.withTag("PebbleKit2ProviderState")
   return PebbleKit2ProviderState(
      enabled = pebbleKit2Tracking(watchConfig, componentState),
      connectedWatches = connectedWatchRows(libPebble),
      activeAppOf = { serial -> activeAppRow(libPebble, libPebble, serial) },
      idColumn = ConnectedWatch.ID,
      notify = { change ->
         val uri = when (change) {
            PebbleKit2Change.ConnectedWatches -> ConnectedWatch.getContentUri(packageName)
            PebbleKit2Change.ActiveApps ->
               Uri.withAppendedPath(PebbleKitProviderContract.getAuthorityUri(packageName), ActiveApp.CONTENT_PATH)
         }
         try {
            context.contentResolver.notifyChange(uri, null)
         } catch (e: SecurityException) {
            logger.e(e) { "Failed to notify $uri - is the provider present in app manifest?" }
         }
      },
      scope = scope,
   )
}

internal enum class PebbleKitQueryRefusal { Disabled, Unauthorized }

internal sealed class PebbleKitQueryDecision {
   data class Admit(val caller: String) : PebbleKitQueryDecision()
   data class Refuse(val reason: PebbleKitQueryRefusal) : PebbleKitQueryDecision()
}

/**
 * Fork: the `.pebblekit` provider's query admission. The toggle is checked before the registry,
 * so a query while PebbleKit 2 is off is refused without resolving the caller's companions; a
 * setting that cannot be read counts as off (see [toggleAllows]).
 */
internal fun pebbleKitQueryDecision(
   pebbleKit2Enabled: () -> Boolean?,
   caller: String?,
   isAuthorized: (String) -> Boolean,
): PebbleKitQueryDecision {
   if (!toggleAllows(pebbleKit2Enabled)) return PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Disabled)
   if (caller == null || !isAuthorized(caller)) {
      return PebbleKitQueryDecision.Refuse(PebbleKitQueryRefusal.Unauthorized)
   }
   return PebbleKitQueryDecision.Admit(caller)
}

// "Pebble Time 4F2A": the model prefix plus four hex digits unique to the device.
private val DEVICE_NAME_SUFFIX = Regex(""" [0-9A-Fa-f]{4}$""")

/**
 * The watch name served to PebbleKit callers: the BLE-advertised name with the device-unique
 * suffix stripped.
 *
 * The full advertised name, and even more so the user's nickname (which `displayName()` would
 * prefer), is identical for every caller, so serving either would hand two companions the
 * shared correlator the per-caller pseudonymous IDs exist to remove. The model prefix keeps the
 * name informative; nothing more specific than the model is served.
 */
internal fun pebbleKitWatchName(advertisedName: String): String =
    advertisedName.replace(DEVICE_NAME_SUFFIX, "")
