package io.rebble.libpebblecommon.pebblekit.two

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.locker.LockerPBWCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

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
 *
 * The constructor takes the narrow seams (a locker UUID flow, a cached-path lookup, a
 * cache-change signal) rather than the services that provide them so unit tests can drive
 * scans directly; [create] does the production wiring.
 */
class PebbleKitCompanionRegistry(
    private val lockerUuids: Flow<List<Uuid>>,
    private val cachedPbws: () -> List<Path>,
    private val pbwFilesChanged: Flow<Unit>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
            // Rebuild on either signal. The locker flow covers install and removal, which is
            // what grants or revokes a companion package's access. The cache signal covers a
            // store install's PBW arriving after its locker row: the download is a file-only
            // write that no database flow surfaces, and it is the event that makes the
            // declaration readable at all.
            combine(
                lockerUuids,
                pbwFilesChanged.onStart { emit(Unit) },
            ) { uuids, _ -> uuids }.collectLatest { uuids ->
                val scanned = withContext(ioDispatcher) { scanCachedPbws(uuids.toSet()) }
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

    private fun scanCachedPbws(installed: Set<Uuid>): Map<String, Set<String>> = buildMap {
        cachedPbws().forEach { path ->
            // A corrupt or partially written PBW must not take down the whole scan, which would
            // revoke access for every other companion app on the device.
            val info = runCatching { PbwApp(path).info }
                .onFailure { logger.w(it) { "Skipping unreadable PBW ${path.name}" } }
                .getOrNull() ?: return@forEach

            // A cached file whose watchapp is not in the locker grants nothing: web-sync
            // removal marks the row deleted without deleting the file, so membership has to be
            // enforced here rather than assumed from cache hygiene.
            val uuid = Uuid.parseOrNull(info.uuid) ?: return@forEach
            if (uuid !in installed) return@forEach

            val packages = info.companionApp?.android?.apps.orEmpty().mapNotNull { it.pkg }.toSet()
            if (packages.isEmpty()) return@forEach

            // The cache can hold several versions of one watchapp, so merge rather than replace.
            merge(info.uuid.lowercase(), packages) { existing, new -> existing + new }
        }
    }

    companion object {
        fun create(
            locker: LockerApi,
            pbwCache: LockerPBWCache,
            scope: LibPebbleCoroutineScope,
        ) = PebbleKitCompanionRegistry(
            lockerUuids = locker.getAllLockerUuids(),
            cachedPbws = pbwCache::cachedPbwPaths,
            pbwFilesChanged = pbwCache.pbwFilesChanged,
            scope = scope,
        )
    }
}
