package dev.gitlive.firebase.firestore

import dev.gitlive.firebase.Firebase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Fork stub for dev.gitlive:firebase-firestore. Behavioral contract: an
 * empty, never-updating store that accepts writes as silent no-ops.
 *
 * - Snapshot flows never emit ([emptyFlow]): observers (FirestoreLocker,
 *   UsersDao's user-doc watch) simply never receive data, which upstream
 *   already treats as "nothing synced yet".
 * - One-shot reads return an exists=false document or an empty query
 *   result, the same shape a brand-new signed-out user would see.
 * - Writes (set/update/delete) no-op successfully rather than throw. Every
 *   write site upstream is gated on a signed-in user and is therefore
 *   unreachable with the permanently-null auth stub; no-op is the
 *   crash-safe choice if a future merge adds an ungated call.
 * - [SnapshotMetadata.isFromCache] is hardwired true: FirestoreLocker
 *   deliberately ignores cache-only snapshots because acting on an empty
 *   cached emission would mass-delete locker apps. The stub's empty results
 *   must never look server-authoritative.
 */
val Firebase.firestore: FirebaseFirestore get() = FirebaseFirestore

object FirebaseFirestore {
    // Assigned once from the DI seam's settings DSL; the value changes
    // nothing but the assignment must compile and hold.
    var settings: FirebaseFirestoreSettings = FirebaseFirestoreSettings()

    fun collection(collectionPath: String): CollectionReference = CollectionReference()

    fun document(documentPath: String): DocumentReference = DocumentReference()
}

class FirebaseFirestoreSettings internal constructor() {
    companion object {
        // Real Firestore uses -1 as the unlimited-cache sentinel; the value
        // is only ever passed back into the inert settings DSL here.
        const val CACHE_SIZE_UNLIMITED: Long = -1L
    }

    class Builder internal constructor() {
        var cacheSettings: LocalCacheSettings =
            LocalCacheSettings.Persistent(CACHE_SIZE_UNLIMITED)
    }
}

sealed class LocalCacheSettings {
    class Persistent internal constructor(val sizeBytes: Long) : LocalCacheSettings() {
        class Builder internal constructor() {
            var sizeBytes: Long = FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED
        }
    }
}

// The two settings builders run their lambdas for fidelity (upstream's DSL
// block assigns through them) but the produced settings configure nothing.
fun firestoreSettings(
    settings: FirebaseFirestoreSettings? = null,
    builder: FirebaseFirestoreSettings.Builder.() -> Unit,
): FirebaseFirestoreSettings {
    FirebaseFirestoreSettings.Builder().apply(builder)
    return settings ?: FirebaseFirestoreSettings()
}

fun persistentCacheSettings(
    builder: LocalCacheSettings.Persistent.Builder.() -> Unit,
): LocalCacheSettings.Persistent =
    LocalCacheSettings.Persistent(
        LocalCacheSettings.Persistent.Builder().apply(builder).sizeBytes
    )

open class Query internal constructor() {
    fun where(filter: FilterBuilder.() -> Filter): Query {
        // Build the filter for compile fidelity; the result cannot narrow an
        // already-empty store.
        FilterBuilder().filter()
        return this
    }

    // Parameter name is load-bearing: upstream calls
    // snapshots(includeMetadataChanges = true).
    fun snapshots(includeMetadataChanges: Boolean = false): Flow<QuerySnapshot> = emptyFlow()

    suspend fun get(): QuerySnapshot = QuerySnapshot()
}

class CollectionReference internal constructor() : Query() {
    fun document(documentPath: String): DocumentReference = DocumentReference()
}

class DocumentReference internal constructor() {
    fun collection(collectionPath: String): CollectionReference = CollectionReference()

    /** Property form (the Query one is a function); upstream uses both. */
    val snapshots: Flow<DocumentSnapshot> get() = emptyFlow()

    suspend fun get(): DocumentSnapshot = DocumentSnapshot(reference = this)

    suspend fun <T> set(data: T, merge: Boolean = false) {}

    suspend fun <T> update(data: T) {}

    suspend fun delete() {}
}

class DocumentSnapshot internal constructor(
    val reference: DocumentReference = DocumentReference(),
) {
    val exists: Boolean get() = false

    /**
     * Upstream guards data() behind exists checks or maps it over (empty)
     * query results, so this is unreachable in practice. If reached with a
     * nullable target type (upstream does data&lt;User?&gt;()), the honest
     * "no data" answer is null; with a non-null target the only honest
     * answer is a throw.
     */
    inline fun <reified T> data(): T =
        if (null is T) {
            @Suppress("UNCHECKED_CAST")
            (null as T)
        } else {
            throw NoSuchElementException("Stub Firestore document has no data")
        }
}

class QuerySnapshot internal constructor() {
    val documents: List<DocumentSnapshot> get() = emptyList()
    val documentChanges: List<DocumentChange> get() = emptyList()
    val metadata: SnapshotMetadata get() = SnapshotMetadata()
}

class DocumentChange internal constructor()

class SnapshotMetadata internal constructor() {
    val isFromCache: Boolean get() = true
}

sealed class Filter {
    class WithConstraint internal constructor() : Filter()
}

class FilterBuilder internal constructor() {
    infix fun <T> FieldPath.equalTo(value: T): Filter.WithConstraint = Filter.WithConstraint()
}

class FieldPath(vararg fieldNames: String)

/**
 * The real type is an Android typealias to the native SDK exception; here it
 * is a plain class. The stub never throws it (no operation can fail), but
 * upstream catch clauses and FirestoreDaoException.fromFirebaseException
 * must compile, including reading the code through the extension below.
 */
class FirebaseFirestoreException internal constructor(
    message: String,
    internal val codeInternal: FirestoreExceptionCode,
) : Exception(message)

val FirebaseFirestoreException.code: FirestoreExceptionCode get() = codeInternal

// Full enum from the real SDK so future upstream merges that name other
// constants keep compiling; the fork only ever names UNAVAILABLE and
// DEADLINE_EXCEEDED.
enum class FirestoreExceptionCode {
    OK,
    CANCELLED,
    UNKNOWN,
    INVALID_ARGUMENT,
    DEADLINE_EXCEEDED,
    NOT_FOUND,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    FAILED_PRECONDITION,
    ABORTED,
    OUT_OF_RANGE,
    UNIMPLEMENTED,
    INTERNAL,
    UNAVAILABLE,
    DATA_LOSS,
    UNAUTHENTICATED,
}

abstract class BaseTimestamp internal constructor()

class Timestamp internal constructor(
    val seconds: Long,
    val nanoseconds: Int,
) : BaseTimestamp()

/**
 * index-ai's TolerantInstantSerializer calls deserialize directly to probe
 * for a native Firestore Timestamp and treats any throw as "not a native
 * timestamp", falling back to its kotlinx struct decode. Always throwing is
 * therefore the behaviorally correct stub: with Firestore removed, no native
 * timestamp can ever appear.
 */
object BaseTimestampSerializer : KSerializer<BaseTimestamp> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.gitlive.firebase.firestore.BaseTimestamp", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): BaseTimestamp =
        throw SerializationException("Stub Firestore cannot decode native timestamps")

    override fun serialize(encoder: Encoder, value: BaseTimestamp): Unit =
        throw SerializationException("Stub Firestore cannot encode native timestamps")
}
