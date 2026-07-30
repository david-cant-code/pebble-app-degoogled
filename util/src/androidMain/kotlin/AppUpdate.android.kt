package coredevices.coreapp.util

import PlatformUiContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork: upstream's android actual wrapped a Play in-app-update
 * AppUpdateInfo. With Play in-app updates removed there is nothing to
 * carry, but the expect declaration (and the UpdateAvailable state that
 * holds it) stays so the common code is untouched; nothing constructs
 * this class.
 */
actual class AppUpdatePlatformContent

/**
 * Fork-owned replacement for upstream's AndroidAppUpdate, which polled the
 * Play in-app-update manager, posted an update notification, and launched
 * the market:// flow pinned to the Play Store package. None of that can
 * work without Play services or a Play-installed APK, and a de-Googled
 * build updates through whatever channel installed it, so the update state
 * is permanently NoUpdateAvailable and no notification channel is created.
 */
class NoOpAppUpdate : AppUpdate {
    override val updateAvailable: StateFlow<AppUpdateState> =
        MutableStateFlow(AppUpdateState.NoUpdateAvailable)

    override fun startUpdateFlow(uiContext: PlatformUiContext, update: AppUpdatePlatformContent) {}
}
