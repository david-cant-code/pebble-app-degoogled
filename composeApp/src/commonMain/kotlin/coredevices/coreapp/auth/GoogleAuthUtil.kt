package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.GoogleAuthUtil
import dev.gitlive.firebase.auth.AuthCredential

/**
 * Fork-owned no-op replacing upstream's expect/actual RealGoogleAuthUtil,
 * which drove Sign in with Google through Play services (Credential
 * Manager backed by GMS, the Identity authorization client, and the
 * googleid library). None of that exists on a de-Googled ROM. The Google
 * sign-in button is hidden via googleAuthEnabled=false, so this binding is
 * the layer behind the hidden UI: anything that still reaches
 * GoogleAuthUtil gets the same null a cancelled sign-in produces, and
 * SilentSignIn restoration is covered by the existing NoOpSilentSignIn.
 */
object NoOpGoogleAuthUtil : GoogleAuthUtil {
    override suspend fun signInGoogle(context: PlatformUiContext): AuthCredential? = null
}
