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
     * Companion packages keyed by the lowercase watchapp UUID that declares them.
     *
     * Null until the first scan completes, which is deliberately distinct from "scanned, found
     * nothing": callers are denied in both cases, so a query arriving before the locker has been
     * read fails closed instead of briefly exposing watch state.
     *
     * Keyed by watchapp rather than flattened because the sender surface has to answer "may this
     * package drive *this* watchapp", and it has to answer it while holding a binder thread, so
     * the per-watchapp breakdown cannot be recomputed on demand.
     */
    @Volatile
    private var companionsByApp: Map<String, Set<String>>? = null

    fun init() {
        scope.launch(CoroutineName("PebbleKitCompanionRegistry")) {
            // Rebuild whenever the locker changes: installing or removing a watchapp is exactly
            // what grants or revokes a companion package's access.
            locker.getAllLockerUuids().collectLatest {
                val scanned = withContext(Dispatchers.IO) { scanCachedPbws() }
                companionsByApp = scanned
                logger.d { "Companion packages across ${scanned.size} watchapps" }
            }
        }
    }

    /** Whether [callingPackage] is a declared companion of any installed watchapp. */
    fun isAuthorized(callingPackage: String?): Boolean {
        if (callingPackage == null) return false
        return companionsByApp?.values?.any { callingPackage in it } == true
    }

    /** Whether [callingPackage] is a declared companion of the watchapp [watchappUuid]. */
    fun isAuthorizedFor(callingPackage: String?, watchappUuid: String?): Boolean {
        if (callingPackage == null || watchappUuid == null) return false
        return companionsByApp?.get(watchappUuid.lowercase())?.contains(callingPackage) == true
    }

    private fun scanCachedPbws(): Map<String, Set<String>> = buildMap {
        pbwCache.cachedPbwPaths().forEach { path ->
            // A corrupt or partially written PBW must not take down the whole scan, which would
            // revoke access for every other companion app on the device.
            val info = runCatching { PbwApp(path).info }
                .onFailure { logger.w(it) { "Skipping unreadable PBW ${path.name}" } }
                .getOrNull() ?: return@forEach

            val packages = info.companionApp?.android?.apps.orEmpty().mapNotNull { it.pkg }.toSet()
            if (packages.isEmpty()) return@forEach

            // The cache can hold several versions of one watchapp, so merge rather than replace.
            merge(info.uuid.lowercase(), packages) { existing, new -> existing + new }
        }
    }
}
