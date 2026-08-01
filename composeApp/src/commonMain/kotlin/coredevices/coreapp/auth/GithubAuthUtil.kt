package coredevices.coreapp.auth

import PlatformUiContext
import coredevices.util.auth.GitHubAuthUtil
import dev.gitlive.firebase.auth.AuthCredential

/**
 * Fork-owned no-op replacing upstream's expect/actual RealGithubAuthUtil,
 * which drove GitHub sign-in through the native Firebase Auth OAuth flow
 * (startActivityForSignInWithProvider). Core-account sign-in is removed in
 * this fork along with the Firebase SDKs it rides on. The GitHub sign-in
 * button is hidden via githubAuthEnabled=false; this binding is the layer
 * behind the hidden UI, returning the same null a cancelled sign-in
 * produces. Same pattern as NoOpGoogleAuthUtil.
 */
object NoOpGithubAuthUtil : GitHubAuthUtil {
    override suspend fun signInGithub(context: PlatformUiContext): AuthCredential? = null
}
