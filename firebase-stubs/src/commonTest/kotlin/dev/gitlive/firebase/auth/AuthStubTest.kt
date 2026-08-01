package dev.gitlive.firebase.auth

import dev.gitlive.firebase.Firebase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the stub's behavioral contract: permanently signed out, sign-in
 * impossible, auth flows terminate. Upstream call sites were audited against
 * exactly these semantics (see the Firebase strip plan), so a change here
 * must re-audit them; these tests make such a change loud.
 */
class AuthStubTest {

    @Test
    fun currentUserIsAlwaysNull() {
        assertNull(Firebase.auth.currentUser)
    }

    @Test
    fun authStateChangedEmitsExactlyOneNullAndCompletes() = runTest {
        // toList only returns because the flow completes; a never-completing
        // stub would hang collectors that upstream expects to finish.
        assertEquals(listOf(null), Firebase.auth.authStateChanged.toList())
    }

    @Test
    fun idTokenChangedEmitsExactlyOneNullAndCompletes() = runTest {
        assertEquals(listOf(null), Firebase.auth.idTokenChanged.toList())
    }

    @Test
    fun signInAnonymouslyThrows() = runTest {
        assertFailsWith<FirebaseAuthException> { Firebase.auth.signInAnonymously() }
    }

    @Test
    fun signInWithCredentialThrows() = runTest {
        assertFailsWith<FirebaseAuthException> {
            Firebase.auth.signInWithCredential(AuthCredential(providerId = "google.com"))
        }
    }

    @Test
    fun signOutIsANoOp() = runTest {
        Firebase.auth.signOut()
    }

    @Test
    fun firebaseUserReadsDegradeToAnonymousEmptyShape() = runTest {
        // Constructible only inside this module; upstream can never hold an
        // instance. If one ever escaped, readers must degrade instead of crash.
        val user = FirebaseUser()
        assertEquals("", user.uid)
        assertTrue(user.isAnonymous)
        assertNull(user.email)
        assertNull(user.displayName)
        assertTrue(user.providerData.isEmpty())
        assertNull(user.getIdToken(false))
        assertNull(user.getIdToken(true))
    }

    @Test
    fun linkWithCredentialThrows() = runTest {
        assertFailsWith<FirebaseAuthException> {
            FirebaseUser().linkWithCredential(AuthCredential(providerId = "apple.com"))
        }
    }

    @Test
    fun collisionExceptionIsCatchableAsFirebaseAuthException() {
        // SignInButton.android.kt catches the collision subtype specifically;
        // the hierarchy must hold for that catch clause to stay meaningful.
        val e: FirebaseAuthException = FirebaseAuthUserCollisionException("collision")
        assertTrue(e is Exception)
    }
}
