package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.AppleAuthUtil
import dev.gitlive.firebase.auth.AuthCredential

/**
 * Fork-owned no-op replacing upstream's expect/actual RealAppleAuthUtil,
 * which drove Sign in with Apple through the native Firebase Auth OAuth
 * flow (startActivityForSignInWithProvider). Core-account sign-in is
 * removed in this fork along with the Firebase SDKs it rides on. The Apple
 * sign-in button is hidden via appleAuthEnabled=false; this binding is the
 * layer behind the hidden UI, returning the same null a cancelled sign-in
 * produces. Same pattern as NoOpGoogleAuthUtil.
 */
object NoOpAppleAuthUtil : AppleAuthUtil {
    override suspend fun signInApple(context: PlatformUiContext): AuthCredential? = null
}
