package coredevices.coreapp

import coredevices.coreapp.auth.NoOpGoogleAuthUtil
import coredevices.coreapp.di.androidDefaultModule
import coredevices.util.CommonBuildKonfig
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.auth.NoOpSilentSignIn
import coredevices.util.auth.SilentSignIn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.koin.dsl.koinApplication

class GmsAuthUnplugTest {
    // The app no longer declares play-services-auth, googleid, or
    // androidx-credentials, but the artifacts stay on the classpath as
    // transitives of firebase-auth (its Android SDK pulls
    // credentials-play-services-auth), so a classpath-absence probe like
    // RingUnplugTest's is only possible once the Firebase strip lands.
    // Until then the enforceable guarantee is the seam itself: whatever
    // Koin hands out for the auth interfaces must be the fork's no-ops,
    // which never touch GMS. Definitions resolve lazily, so only the two
    // probed bindings are instantiated here.
    @Test
    fun authSeamResolvesToTheForkNoOps() {
        val koin = koinApplication { modules(androidDefaultModule) }.koin
        assertSame(NoOpGoogleAuthUtil, koin.get<GoogleAuthUtil>())
        assertSame(NoOpSilentSignIn, koin.get<SilentSignIn>())
    }

    // gradle.properties ships googleAuthEnabled=false so SignInButton never
    // renders the Google option a builder cannot back with GMS.
    @Test
    fun googleAuthFlagIsOffInTheShippedConfig() {
        assertFalse(CommonBuildKonfig.GOOGLE_AUTH_ENABLED)
    }
}
