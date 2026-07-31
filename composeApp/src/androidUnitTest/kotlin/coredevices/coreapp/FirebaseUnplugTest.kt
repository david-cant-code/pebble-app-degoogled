package coredevices.coreapp

import coredevices.coreapp.account.SignedOutUsersDao
import coredevices.coreapp.auth.NoOpAppleAuthUtil
import coredevices.coreapp.auth.NoOpGithubAuthUtil
import coredevices.coreapp.di.androidDefaultModule
import coredevices.coreapp.di.utilModule
import coredevices.firestore.UsersDao
import coredevices.util.CommonBuildKonfig
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.GitHubAuthUtil
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.koin.dsl.koinApplication

/**
 * The Firebase strip's three enforcement layers, each probed independently:
 * the SDKs are off the classpath entirely, the DI seams resolve to the
 * fork's inert bindings, and the sign-in feature flags are off. The
 * classpath probes are the ones GmsAuthUnplugTest documented as deferred
 * until firebase-auth (whose Android SDK dragged the GMS artifacts back in)
 * was gone.
 */
class FirebaseUnplugTest {

    private fun assertAbsent(className: String) {
        assertFailsWith<ClassNotFoundException>("$className should not be on the classpath") {
            Class.forName(className)
        }
    }

    @Test
    fun firebaseSdksAreOffTheClasspath() {
        assertAbsent("com.google.firebase.FirebaseApp")
        assertAbsent("com.google.firebase.auth.FirebaseAuth")
        assertAbsent("com.google.firebase.firestore.FirebaseFirestore")
        assertAbsent("com.google.firebase.messaging.FirebaseMessaging")
    }

    @Test
    fun gmsRidersOfFirebaseAuthAreOffTheClasspath() {
        // These arrived only as firebase-auth transitives (gms auth/fido/
        // signin, Play integrity, the credentials Play bridge).
        assertAbsent("com.google.android.gms.auth.api.signin.GoogleSignIn")
        assertAbsent("com.google.android.gms.common.api.GoogleApiClient")
        assertAbsent("com.google.android.play.core.integrity.IntegrityManagerFactory")
        assertAbsent("androidx.credentials.playservices.CredentialProviderPlayServicesImpl")
    }

    @Test
    fun gmsRidersOfHealthKmpAreOffTheClasspath() {
        // health-kmp's Google Fit backend deps, excluded in the build files;
        // the Health Connect client itself must survive the excision.
        assertAbsent("com.google.android.gms.fitness.Fitness")
        Class.forName("androidx.health.connect.client.HealthConnectClient")
    }

    @Test
    fun authStubIsPermanentlySignedOut() {
        assertNull(Firebase.auth.currentUser)
    }

    @Test
    fun authSeamsResolveToTheForkNoOps() {
        val koin = koinApplication { modules(androidDefaultModule) }.koin
        assertSame(NoOpAppleAuthUtil, koin.get<AppleAuthUtil>())
        assertSame(NoOpGithubAuthUtil, koin.get<GitHubAuthUtil>())
    }

    @Test
    fun usersDaoSeamResolvesToTheSignedOutFork() {
        // Guards against UsersDaoImpl coming back: its startup observer
        // loops forever against the permanently-null auth stub on installs
        // that ever had an account.
        val koin = koinApplication { modules(utilModule) }.koin
        assertSame(SignedOutUsersDao, koin.get<UsersDao>())
    }

    @Test
    fun allProviderSignInFlagsAreOffInTheShippedConfig() {
        assertFalse(CommonBuildKonfig.GOOGLE_AUTH_ENABLED)
        assertFalse(CommonBuildKonfig.APPLE_AUTH_ENABLED)
        assertFalse(CommonBuildKonfig.GITHUB_AUTH_ENABLED)
    }
}
