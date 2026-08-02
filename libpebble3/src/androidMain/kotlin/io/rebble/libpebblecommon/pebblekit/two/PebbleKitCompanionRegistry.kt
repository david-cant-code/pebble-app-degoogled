package io.rebble.libpebblecommon.pebblekit.two

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.locker.LockerPBWCache
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = Logger.withTag("PebbleKitCompanionRegistry")

/**
 * Which Android packages are authorized to use the PebbleKit2 API surface.
 *
 * A package is authorized when it is named in the `companionApp.android.apps[].pkg` list of a
 * watchapp the user has actually installed, which is the same relationship
 * [PebbleSenderReceiver] already requires before it will relay app messages or timeline pins.
 * Applying it to the read path as well means the authorization is rooted in a deliberate user
 * action (installing that watchapp) rather than in the caller merely existing on the device.
 *
 * The set is precomputed rather than resolved per query for two reasons. Resolving on demand
 * would mean opening PBW archives while holding a binder thread, and the obvious API for
 * locating a PBW ([LockerPBWCache.getPBWFileForApp]) falls through to a network fetch on a cache
 * miss, which would let an unauthorized caller drive traffic just by querying. Only
 * already-cached PBWs are consulted here.
 */
class PebbleKitCompanionRegistry(
    private val locker: LockerApi,
    private val pbwCache: LockerPBWCache,
    private val scope: LibPebbleCoroutineScope,
) {
    /**
     * Null until the first scan completes, which is deliberately distinct from "scanned, found
     * nothing": callers are denied in both cases, so a query arriving before the locker has been
     * read fails closed instead of briefly exposing watch state.
     */
    @Volatile
    private var authorizedPackages: Set<String>? = null

    fun init() {
        scope.launch(CoroutineName("PebbleKitCompanionRegistry")) {
            // Rebuild whenever the locker changes: installing or removing a watchapp is exactly
            // what grants or revokes a companion package's access.
            locker.getAllLockerUuids().collectLatest {
                val scanned = withContext(Dispatchers.IO) { scanCachedPbws() }
                authorizedPackages = scanned
                logger.d { "Authorized PebbleKit companion packages: ${scanned.size}" }
            }
        }
    }

    fun isAuthorized(callingPackage: String?): Boolean {
        if (callingPackage == null) return false
        return authorizedPackages?.contains(callingPackage) == true
    }

    private fun scanCachedPbws(): Set<String> = buildSet {
        pbwCache.cachedPbwPaths().forEach { path ->
            // A corrupt or partially written PBW must not take down the whole scan, which would
            // revoke access for every other companion app on the device.
            runCatching { PbwApp(path).info.companionApp?.android?.apps.orEmpty() }
                .onFailure { logger.w(it) { "Skipping unreadable PBW ${path.name}" } }
                .getOrNull()
                ?.forEach { app -> app.pkg?.let(::add) }
        }
    }
}
