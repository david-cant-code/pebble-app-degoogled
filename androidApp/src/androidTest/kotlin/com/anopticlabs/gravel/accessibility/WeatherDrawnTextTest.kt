package com.anopticlabs.gravel.accessibility

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.pkjs.FixedNetworkDenyEnforcement
import com.anopticlabs.gravel.weather.WeatherConsentDialog
import com.anopticlabs.gravel.weather.WeatherShareDialog
import com.russhwolf.settings.SharedPreferencesSettings
import coredevices.coreapp.ui.screens.WatchappPrivacyChoices
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import io.rebble.libpebblecommon.connection.FakeLibPebble
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.time.Duration.Companion.hours

/**
 * The weather question after the update, the Settings switch's popup, and setup's Privacy screen with its confirm
 * popup. The share switch's label is not checked: its text is under clearAndSetSemantics (WeatherShareSwitch).
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.WeatherDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class WeatherDrawnTextTest(check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "weather-drawn-text-test"

    @After
    fun deletePrefs() {
        context.deleteSharedPreferences(prefsName)
    }

    @Test
    fun updateQuestion() {
        drawn.setContent { WeatherShareDialog(1.hours) {} }
        drawn.compose.waitUntil(5_000) { runCatching { drawn.compose.onNodeWithText("Done").assertIsEnabled() }.isSuccess }
        drawn.assertReadable()
    }

    @Test
    fun settingsPopup() {
        drawn.setContent {
            WeatherConsentDialog(
                title = "Share your location for weather?",
                interval = 1.hours,
                confirmLabel = "Share and turn on",
                dismissLabel = "Cancel",
                onConfirm = {},
                onDismiss = {},
            )
        }
        drawn.assertReadable()
    }

    @Test
    fun setupPrivacyScreen() {
        drawn.setSetupContent { SetupPrivacy() }
        drawn.assertReadable()
    }

    @Test
    fun setupConfirmPopup() {
        drawn.setSetupContent { SetupPrivacy() }
        drawn.compose.onNodeWithText("Finish setup").performScrollTo().performClick()
        drawn.compose.onNodeWithText("Confirm").assertIsEnabled()
        drawn.assertReadable()
    }

    @Composable
    private fun SetupPrivacy() {
        val holder = remember {
            CoreConfigHolder(
                CoreConfig(lastSeenWhatsNewVersion = Int.MAX_VALUE),
                SharedPreferencesSettings(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).also { it.edit().clear().commit() }),
                Json,
            )
        }
        val libPebble = remember { FakeLibPebble() }
        val enforcement = remember { FixedNetworkDenyEnforcement(primaryLayerActive = true) }
        WatchappPrivacyChoices(libPebble, enforcement, holder, onWeatherChosen = {}) {}
    }
}
