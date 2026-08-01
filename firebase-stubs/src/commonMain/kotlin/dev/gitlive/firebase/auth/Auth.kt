package dev.gitlive.firebase.auth

import dev.gitlive.firebase.Firebase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fork stub for dev.gitlive:firebase-auth. Behavioral contract: the app is
 * permanently signed out.
 *
 * - [FirebaseAuth.currentUser] is always null, so every feature gated on a
 *   signed-in user (Firestore locker, hearts, contact developer, remote bug
 *   reports, cloud STT auth) deterministically takes its existing signed-out
 *   branch.
 * - The auth-state flows emit exactly one null and complete. Observers like
 *   FirestoreLocker.init and FirestoreKnownWatchesSync collect the null,
 *   short-circuit, and their collection ends cleanly instead of parking on
 *   a never-emitting flow.
 * - Sign-in attempts throw [FirebaseAuthException]: sign-in is impossible by
 *   design in this fork, and a throw is loud if some future merge reintroduces
 *   a call path. The only startup-path caller upstream (UsersDaoImpl's
 *   anonymous sign-in) already wraps the call in try/catch, and the fork
 *   additionally replaces that DAO at the Koin seam.
 * - [FirebaseUser] has an internal constructor and no factory: no instance
 *   can ever exist outside this module's tests, which is itself the strongest
 *   signed-out guarantee.
 */
val Firebase.auth: FirebaseAuth get() = FirebaseAuth

object FirebaseAuth {
    val currentUser: FirebaseUser? get() = null

    /** Emits one null (the permanent signed-out state) and completes. */
    val authStateChanged: Flow<FirebaseUser?> get() = flowOf(null)

    /** Same contract as [authStateChanged]: one null, then completion. */
    val idTokenChanged: Flow<FirebaseUser?> get() = flowOf(null)

    suspend fun signInAnonymously(): AuthResult =
        throw FirebaseAuthException("Firebase authentication is removed in this fork")

    suspend fun signInWithCredential(authCredential: AuthCredential): AuthResult =
        throw FirebaseAuthException("Firebase authentication is removed in this fork")

    /** Already signed out, forever: nothing to do. */
    suspend fun signOut() {}
}

class FirebaseUser internal constructor() {
    // Inert values rather than throws: if an instance ever escaped (it
    // cannot, see the class-level rationale above), readers degrade to the
    // anonymous/empty shape instead of crashing a render or sync path.
    val uid: String get() = ""
    val isAnonymous: Boolean get() = true
    val email: String? get() = null
    val displayName: String? get() = null
    val providerData: List<UserInfo> get() = emptyList()

    suspend fun getIdToken(forceRefresh: Boolean): String? = null

    suspend fun linkWithCredential(credential: AuthCredential): AuthResult =
        throw FirebaseAuthException("Firebase authentication is removed in this fork")
}

class UserInfo internal constructor() {
    val providerId: String get() = ""
}

/**
 * Kept open with a providerId like the real class: upstream reads
 * credential.providerId after a successful provider sign-in. Nothing in the
 * fork can construct one (the no-op auth utils return null), which is the
 * point.
 */
open class AuthCredential internal constructor(val providerId: String)

/** Never instantiated: every sign-in path throws before producing a result. */
class AuthResult internal constructor()

open class FirebaseAuthException internal constructor(message: String) : Exception(message)

/**
 * Upstream catches this around linkWithCredential to offer "switch to the
 * existing account". The stub never throws it (sign-in cannot progress far
 * enough to collide), but the catch clause must still compile.
 */
class FirebaseAuthUserCollisionException internal constructor(message: String) :
    FirebaseAuthException(message)
