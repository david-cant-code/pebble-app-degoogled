package coredevices.coreapp.di

import CoreAppVersion
import PlatformContext
import PlatformShareLauncher
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import coredevices.analytics.createAndroidAnalytics
import coredevices.coreapp.BuildConfig
import coredevices.coreapp.PebbleBackgroundManager
import coredevices.coreapp.auth.RealAppleAuthUtil
import coredevices.coreapp.auth.RealGithubAuthUtil
import coredevices.coreapp.auth.RealGoogleAuthUtil
import coredevices.coreapp.util.AndroidAppUpdate
import coredevices.coreapp.util.AppUpdate
import coredevices.coreapp.model.CactusModelProvider
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
import coredevices.util.auth.SilentSignIn
import coredevices.util.integrations.AndroidOAuthLauncher
import coredevices.util.integrations.OAuthLauncher
import coredevices.util.models.ModelDownloadManager
import coredevices.util.transcription.CactusModelPathProvider
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.toJavaDuration

val androidDefaultModule = module {
    singleOf(::RealGoogleAuthUtil) binds arrayOf(GoogleAuthUtil::class, SilentSignIn::class)
    singleOf(::RealAppleAuthUtil) bind AppleAuthUtil::class
    singleOf(::RealGithubAuthUtil) bind GitHubAuthUtil::class
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
    single { CoreAppVersion(BuildConfig.VERSION_NAME) }
    factory { AppUpdateManagerFactory.create(get()) }
    singleOf(::PlatformContext)
    singleOf(::AndroidPermissionRequester) bind PermissionRequester::class
    singleOf(::AndroidCompanionDevice) bind CompanionDevice::class
    singleOf(::AndroidAppUpdate) bind AppUpdate::class
    single {
        // Ring support is unplugged in this fork, so the watch delegate's
        // permissions are the whole set; the upstream version unioned in the
        // RingDelegate permissions when CoreConfig.enableIndex was on.
        val pebbleDelegate = get<PebbleAndroidDelegate>()
        RequiredPermissions(pebbleDelegate.requiredPermissions)
    }
    single { createAndroidAnalytics(get()) }
    singleOf(::ModelDownloadManager)
    // Fork binding: upstream registers its Cactus model provider inside the
    // unplugged :experimental module; without this, on-device Cactus STT
    // falls back to a provider that throws (utilModule) and dictation dies.
    singleOf(::CactusModelProvider) bind CactusModelPathProvider::class
    singleOf(::PebbleBackgroundManager)
}