package coredevices.coreapp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.russhwolf.settings.Settings
import coredevices.pebble.ui.WHATS_NEW_VERSION
import coredevices.pebble.ui.WhatsNewPopup
import coredevices.util.CoreConfigHolder
import org.koin.compose.koinInject

/**
 * One-time update notice, shown over the home screen. Only fires when onboarding has
 * already happened (so it never overlaps the onboarding privacy choice) and the user's
 * last-seen revision is behind [WHATS_NEW_VERSION]. Dismissing stamps the current version
 * so it does not reappear. The popup itself and the entry list live in
 * `coredevices.pebble.ui.WhatsNew`, where Settings > About reopens the same popup on
 * demand.
 */
@Composable
fun WhatsNewDialog() {
    val settings: Settings = koinInject()
    val configHolder: CoreConfigHolder = koinInject()
    val config by configHolder.config.collectAsState()

    // Guard: only for installs that finished onboarding in a previous version. A fresh
    // install stamps lastSeenWhatsNewVersion at the end of onboarding, so this is false
    // for it and the dialog stays hidden.
    val onboarded = settings.getBoolean(SHOWN_ONBOARDING, false)
    if (!onboarded || config.lastSeenWhatsNewVersion >= WHATS_NEW_VERSION) {
        return
    }

    WhatsNewPopup(
        requireExplicitAck = true,
        onClose = {
            configHolder.update(config.copy(lastSeenWhatsNewVersion = WHATS_NEW_VERSION))
        },
    )
}
