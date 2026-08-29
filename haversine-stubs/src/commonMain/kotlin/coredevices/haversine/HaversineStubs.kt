package coredevices.haversine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork stubs for the `io.github.coredevices.haversine` Ring satellite library,
 * declared under the same fully-qualified names so `:libindex` compiles
 * unchanged against them.
 *
 * Behavioral contract: no Ring can ever be seen, paired, or driven.
 *
 * - The satellite classes ([KMPHaversineSatellite], [KMPHaversineSatelliteState],
 *   [KMPHaversineSatelliteManager]) have private constructors and no factory,
 *   so no instance can exist anywhere in the app. Every libindex code path
 *   that would act on a ring first needs one of these in hand, which makes
 *   this the strongest possible guarantee that the dead Ring runtime stays
 *   dead. Their members are still inert rather than throwing, matching the
 *   `:firebase-stubs` house rule: if an instance ever did escape, callers
 *   degrade to the empty shape instead of crashing a scan or sync path.
 * - The three static entry points that need no instance, the advertisement
 *   fingerprint parser and the failsafe and production-test-mode checks,
 *   answer "no ring here" (`null` / `false`) so a BLE scan that reaches them
 *   ignores the packet.
 * - The two delegate interfaces are mirrored exactly; libindex implements
 *   [CollectionIndexStorage] and only calls [KMPHaversineHacksDelegate]
 *   through a satellite it can never obtain.
 *
 * Only what libindex references is declared. A new upstream use of the
 * library's API fails the build here until this surface is extended, which
 * is intended: it forces a look at whether the new call belongs to the dead
 * Ring runtime (extend the stub) or to something the fork actually needs to
 * think about. The settings-level substitution rewrites every reference to
 * the real artifact's coordinate to this module, so the two cannot meet on
 * a classpath; were that rule ever lost, the AAR would return silently,
 * which AppClasspathSentinelTest in :androidApp fails on.
 */
interface CollectionIndexStorage {
    val lastSuccessfulCollectionIndex: StateFlow<Int?>
    fun setLastSuccessfulCollectionIndex(index: Int?)
}

interface KMPHaversineHacksDelegate {
    fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite): Boolean
    fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite)
}

class KMPHaversineAdvertisement private constructor() {
    companion object {
        /**
         * The real parser decodes the ring's manufacturer-data state
         * fingerprint. `null` is the library's own "not a ring advertisement"
         * answer, and libindex's scanner drops the packet on it, so this is
         * the inert value that keeps a scan quiet rather than one that
         * invents a device.
         */
        @Suppress("UNUSED_PARAMETER")
        fun parseToStateFingerprint(data: ByteArray): Long? = null
    }
}

/** No fingerprint ever parses, so nothing can match the failsafe pattern either. */
@Suppress("UNUSED_PARAMETER")
fun fingerprintMatchesFailsafe(fingerprint: Long): Boolean = false

/**
 * Same answer for the production-test-mode pattern: libindex's scanner
 * classifies a ring image from this and the failsafe check, and a fingerprint
 * that never parses can match neither.
 */
@Suppress("UNUSED_PARAMETER")
fun fingerprintMatchesPTF(fingerprint: Long): Boolean = false

class KMPHaversineSatellite private constructor() {
    val id: String get() = ""
    val name: String? get() = null
    val state: StateFlow<KMPHaversineSatelliteState?> = MutableStateFlow(null)
    suspend fun eraseCollections() {}

    /**
     * The real call invalidates the ring's primary image to drop it into
     * failsafe for repair. Inert here for the same reason as the rest of the
     * instance surface: nothing can hold a satellite, and if one were reached
     * anyway there is no ring behind it to repair.
     */
    suspend fun forceFailsafe() {}
}

class KMPHaversineSatelliteState private constructor() {
    val firmwareVersion: String get() = ""
    val serialNumber: String get() = ""
    val programmedSerialNumber: String? get() = null
}

class KMPHaversineSatelliteManager private constructor() {
    /** Never emits a ring: the flow is created holding null and nothing writes to it. */
    val lastRing: StateFlow<KMPHaversineSatellite?> = MutableStateFlow(null)

    @Suppress("UNUSED_PARAMETER")
    suspend fun getSatelliteById(id: String): KMPHaversineSatellite? = null
}
