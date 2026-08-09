package coredevices.coreapp

import coredevices.coreapp.account.SignedOutUsersDao
import coredevices.firestore.EncryptionInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Pins SignedOutUsersDao's documented flow contract, which the seam identity
 * test (FirebaseUnplugTest) cannot see. The contract is load-bearing:
 * PebbleTokenProvider.getDevToken calls user.firstOrNull() with no timeout on
 * the PKJS account-token path (runBlocking on a WebView thread), so a
 * refactor to a non-replaying open flow would hang that thread silently, and
 * a completing flow would change collectAsState subscription semantics for
 * UI consumers. Same rationale as the firebase-stubs invariant suites: make
 * a contract change loud.
 */
class SignedOutUsersDaoTest {

    @Test
    fun userReplaysNullImmediately() = runTest {
        // Must complete, not suspend: the timeout only bounds the failure
        // mode (virtual time makes a hang fail fast instead of forever).
        assertNull(withTimeout(1_000) { SignedOutUsersDao.user.firstOrNull() })
    }

    @Test
    fun loginEventsStaysOpenAndNeverFires() = runTest {
        // Distinguishes the two wrong shapes: a completed empty flow makes
        // first() throw NoSuchElementException (fails the assert), while the
        // contractual open-never-firing flow times out under virtual time.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) { SignedOutUsersDao.loginEvents.first() }
        }
    }

    @Test
    fun updatersReturnNormally() = runTest {
        SignedOutUsersDao.updateTodoBlockId("block")
        SignedOutUsersDao.updateNotionPageId("page")
        SignedOutUsersDao.initUserDevToken(null)
        SignedOutUsersDao.updateLastConnectedWatch("serial")
        SignedOutUsersDao.updateRingLifetimeCollectionCount("serial", 0)
        SignedOutUsersDao.updateEncryptionInfo(
            EncryptionInfo(keyFingerprint = "", createdAt = "", keyBackupLocation = "")
        )
        SignedOutUsersDao.init()
    }
}
