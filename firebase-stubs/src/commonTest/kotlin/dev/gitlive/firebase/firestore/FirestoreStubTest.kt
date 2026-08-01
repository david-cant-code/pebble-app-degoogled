package dev.gitlive.firebase.firestore

import dev.gitlive.firebase.Firebase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the stub's behavioral contract: an empty, never-updating store with
 * no-op writes, whose results never look server-authoritative. See the
 * class-level rationale in Firestore.kt for why each choice is load-bearing.
 */
class FirestoreStubTest {

    @Test
    fun settingsDslFromUpstreamDiSeamRunsToCompletion() {
        // Mirrors the exact utilModule block; the assignment through the two
        // builder DSLs must compile and hold.
        val store = Firebase.firestore.apply {
            settings = firestoreSettings {
                cacheSettings = persistentCacheSettings {
                    sizeBytes = FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED
                }
            }
        }
        assertEquals(FirebaseFirestore, store)
    }

    @Test
    fun documentGetReturnsNonExistentSnapshot() = runTest {
        val snapshot = Firebase.firestore.document("users/abc").get()
        assertFalse(snapshot.exists)
    }

    @Test
    fun dataWithNullableTargetReturnsNull() = runTest {
        // UsersDao reads data<User?>(); null is the honest empty-store answer.
        val snapshot = Firebase.firestore.document("users/abc").get()
        assertNull(snapshot.data<String?>())
    }

    @Test
    fun dataWithNonNullTargetThrows() = runTest {
        val snapshot = Firebase.firestore.document("users/abc").get()
        assertFailsWith<NoSuchElementException> { snapshot.data<String>() }
    }

    @Test
    fun documentSnapshotsFlowNeverEmits() = runTest {
        assertTrue(Firebase.firestore.document("users/abc").snapshots.toList().isEmpty())
    }

    @Test
    fun querySnapshotsFlowNeverEmits() = runTest {
        // Named argument mirrors FirestoreLocker's call shape.
        val emissions = Firebase.firestore.collection("lockers")
            .document("uid")
            .collection("entries")
            .snapshots(includeMetadataChanges = true)
            .toList()
        assertTrue(emissions.isEmpty())
    }

    @Test
    fun filteredQueryGetReturnsEmptyResult() = runTest {
        // Mirrors FirestoreLocker.removeLockerEntryForUser's query shape.
        val result = Firebase.firestore.collection("entries")
            .where { FieldPath("uuid") equalTo "some-uuid" }
            .get()
        assertTrue(result.documents.isEmpty())
        assertTrue(result.documentChanges.isEmpty())
    }

    @Test
    fun queryResultsNeverLookServerAuthoritative() = runTest {
        // FirestoreLocker mass-deletes local apps on an authoritative empty
        // snapshot; the stub's empties must always read as cache-only.
        assertTrue(Firebase.firestore.collection("x").get().metadata.isFromCache)
    }

    @Test
    fun writesAreSilentNoOps() = runTest {
        val doc = Firebase.firestore.collection("lockers").document("uid")
        doc.set("payload")
        doc.set(mapOf("k" to "v"), merge = true)
        doc.update(mapOf("k" to "v"))
        doc.update("k" to "v")
        doc.delete()
    }

    @Test
    fun exceptionCodeIsReadableThroughTheExtension() {
        // FirestoreDaoException.fromFirebaseException reads e.code and names
        // UNAVAILABLE/DEADLINE_EXCEEDED in a when.
        val e = FirebaseFirestoreException("offline", FirestoreExceptionCode.UNAVAILABLE)
        assertEquals(FirestoreExceptionCode.UNAVAILABLE, e.code)
        assertEquals("UNAVAILABLE", e.code.name)
    }

    @Test
    fun timestampIsABaseTimestampWithReadableComponents() {
        val ts = Timestamp(seconds = 12L, nanoseconds = 34)
        assertTrue(ts is BaseTimestamp)
        assertEquals(12L, ts.seconds)
        assertEquals(34, ts.nanoseconds)
    }

    @Test
    fun baseTimestampSerializerAlwaysThrowsOnDeserialize() {
        // TolerantInstantSerializer probes with deserialize and treats any
        // throw as "not a native timestamp", falling back to struct decode.
        assertFailsWith<SerializationException> {
            Json.decodeFromString(BaseTimestampSerializer, "0")
        }
    }
}
