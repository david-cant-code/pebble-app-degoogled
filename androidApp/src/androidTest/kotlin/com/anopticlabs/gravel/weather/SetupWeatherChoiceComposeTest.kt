package com.anopticlabs.gravel.weather

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.pkjs.FixedNetworkDenyEnforcement
import com.russhwolf.settings.SharedPreferencesSettings
import coredevices.coreapp.ui.screens.WatchappPrivacyChoices
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.weatherOn
import io.rebble.libpebblecommon.connection.FakeLibPebble
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Setup's Privacy screen and the weather choice it records, against a config in a preferences file of its own.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.weather.SetupWeatherChoiceComposeTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class SetupWeatherChoiceComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "setup-weather-choice-test"
    private val holder = CoreConfigHolder(
        CoreConfig(lastSeenWhatsNewVersion = Int.MAX_VALUE),
        SharedPreferencesSettings(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        }),
        Json,
    )
    private val untouched = holder.config.value

    /** weatherOn as each weather fetch setup starts sees it. */
    private val fetches = mutableListOf<Boolean>()

    @After
    fun deletePrefs() {
        context.deleteSharedPreferences(prefsName)
    }

    private fun show() = compose.setContent {
        WatchappPrivacyChoices(
            FakeLibPebble(),
            FixedNetworkDenyEnforcement(primaryLayerActive = true),
            holder,
            onWeatherChosen = { fetches += holder.config.value.weatherOn },
        ) {}
    }

    private val shareSwitch get() = compose.onNode(hasContentDescription(WEATHER_SHARE_LABEL) and isToggleable())

    /** "Finish setup" sits on the scrolling screen; the confirm dialog's buttons do not scroll. */
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (text == "Finish setup") node.performScrollTo()
        node.performClick()
    }

    private fun offerDue() = weatherOfferDue(onboarded = true, holder.config.value, Int.MAX_VALUE)

    @Test
    fun confirmingWithTheSwitchUntouchedLeavesWeatherOff() {
        show()
        shareSwitch.assertIsOff()
        click("Finish setup")
        compose.onNodeWithText("Gravel's own weather (sends your weather locations to Core Devices): Off", substring = true)
            .assertExists()
        click("Confirm")
        assertEquals(listOf(false), fetches)
        assertFalse(holder.config.value.weatherOn)
        assertFalse(holder.config.value.fetchWeather)
        assertFalse(offerDue(), "the update question must not follow setup")
        compose.onNodeWithText("Gravel's own weather is off", substring = true).assertExists()
    }

    @Test
    fun backRecordsNothing() {
        show()
        shareSwitch.performScrollTo().performClick()
        click("Finish setup")
        click("Back")
        assertEquals(untouched, holder.config.value)
        assertEquals(emptyList(), fetches)
    }

    @Test
    fun flippingTheSwitchThenConfirmingShares() {
        show()
        shareSwitch.performScrollTo().performClick()
        click("Finish setup")
        compose.onNodeWithText("Gravel's own weather (sends your weather locations to Core Devices): On", substring = true)
            .assertExists()
        click("Confirm")
        assertEquals(listOf(true), fetches)
        assertTrue(holder.config.value.weatherOn)
        assertFalse(offerDue(), "the update question must not follow setup")
    }

    @Test
    fun aRerunAfterAnEarlierConfirmShowsTheStoredChoice() {
        holder.agreeToWeather()
        show()
        shareSwitch.assertIsOn()
    }

    @Test
    fun answeringOffOnARerunStartsTheClearingFetch() {
        holder.agreeToWeather()
        show()
        shareSwitch.performScrollTo().performClick()
        click("Finish setup")
        click("Confirm")
        assertEquals(listOf(false), fetches)
        assertFalse(holder.config.value.weatherAgreed)
    }

    private val watchappSentence = "can still get weather through Core Devices' weather server"

    @Test
    fun withWatchappInternetOffTheWatchappWeatherSentenceIsLeftOut() {
        show()
        click("Finish setup")
        click("Confirm")
        compose.onAllNodesWithText(watchappSentence, substring = true).assertCountEquals(0)
    }

    @Test
    fun withWatchappInternetAllowedTheWatchappWeatherSentenceShows() {
        show()
        // The first switch on the screen is "Allow internet access".
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        click("Finish setup")
        click("Confirm")
        compose.onNodeWithText(watchappSentence, substring = true).assertExists()
    }
}
