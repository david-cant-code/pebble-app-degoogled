package coredevices.coreapp.di

import CoreAppVersion
import PlatformContext
import PlatformShareLauncher
import android.content.Context
import coredevices.analytics.createAndroidAnalytics
import coredevices.coreapp.PebbleBackgroundManager
import coredevices.coreapp.appVersionName
import coredevices.coreapp.auth.NoOpAppleAuthUtil
import coredevices.coreapp.auth.NoOpGithubAuthUtil
import coredevices.coreapp.auth.NoOpGoogleAuthUtil
import coredevices.coreapp.AndroidInferenceBoost
import coredevices.coreapp.util.AppUpdate
import coredevices.coreapp.util.NoOpAppUpdate
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.pebble.PebbleAndroidDelegate
import coredevices.util.AndroidCompanionDevice
import coredevices.util.AndroidPermissionRequester
import coredevices.util.AndroidPlatform
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.CompanionDevice
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.PermissionRequester
import coredevices.util.Platform
import coredevices.util.RequiredPermissions
import coredevices.util.auth.GitHubAuthUtil
import coredevices.util.auth.NoOpSilentSignIn
import coredevices.util.auth.SilentSignIn
import coredevices.util.integrations.AndroidOAuthLauncher
import coredevices.util.integrations.OAuthLauncher
import coredevices.util.security.KeystoreSecretCipher
import coredevices.util.security.SecretCipher
import coredevices.util.models.ModelDownloadManager
import coredevices.util.transcription.CactusModelPathProvider
import coredevices.util.transcription.InferenceBoost
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.toJavaDuration

val androidDefaultModule = module {
    // Fork: Sign in with Google needs Play services; see NoOpGoogleAuthUtil.
    single<GoogleAuthUtil> { NoOpGoogleAuthUtil }
    single<SilentSignIn> { NoOpSilentSignIn }
    // Fork: Apple/GitHub sign-in rode the native Firebase Auth OAuth flow,
    // removed with the Firebase strip; see NoOpAppleAuthUtil/NoOpGithubAuthUtil.
    single<AppleAuthUtil> { NoOpAppleAuthUtil }
    single<GitHubAuthUtil> { NoOpGithubAuthUtil }
    factory { params ->
        OkHttp.create {
            config {
                readTimeout(params.get<Duration>().toJavaDuration())
            }
        }
    } bind HttpClientEngine::class
    singleOf(::PlatformShareLauncher)
    singleOf(::AndroidPlatform) bind Platform::class
    singleOf(::AndroidOAuthLauncher) bind OAuthLauncher::class
    singleOf(::KeystoreSecretCipher) bind SecretCipher::class
    single { CoreAppVersion(get<Context>().appVersionName) }
    singleOf(::PlatformContext)
    singleOf(::AndroidPermissionRequester) bind PermissionRequester::class
    singleOf(::AndroidCompanionDevice) bind CompanionDevice::class
    // Fork: Play in-app updates removed; see NoOpAppUpdate.
    singleOf(::NoOpAppUpdate) bind AppUpdate::class
    single {
        // Ring support is unplugged in this fork, so the watch delegate's
        // permissions are the whole set; the upstream version unioned in the
        // RingDelegate permissions when CoreConfig.enableIndex was on.
        val pebbleDelegate = get<PebbleAndroidDelegate>()
        RequiredPermissions(pebbleDelegate.requiredPermissions)
    }
    single { createAndroidAnalytics(get()) }
    singleOf(::ModelDownloadManager)
    // Fork binding: upstream registers its model provider inside the
    // unplugged :experimental module; without this, on-device STT falls
    // back to a provider that throws (utilModule) and dictation dies.
    // The HttpClient is watchModule's shared app-graph single, the same one
    // the verified firmware installer downloads through. The configured-
    // model accessor closes over the config flow so the provider can
    // resolve "the chosen model" without owning config plumbing.
    single {
        val coreConfigFlow = get<coredevices.util.CoreConfigFlow>()
        WhisperModelProvider(androidContext(), get(), get()) {
            coreConfigFlow.value.sttConfig.modelName
        }
    } bind CactusModelPathProvider::class
    // Fork binding: upstream's inference boost rides in the unplugged
    // :experimental module, so utilModule's getOrNull fallback always found
    // nothing and local transcription ran at background priority whenever
    // the watch-connection foreground service was off.
    single { AndroidInferenceBoost(androidContext()) } bind InferenceBoost::class
    singleOf(::PebbleBackgroundManager)
}