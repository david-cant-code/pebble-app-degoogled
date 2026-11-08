package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anopticlabs.gravel.weather.WeatherShareDialog
import com.anopticlabs.gravel.weather.applyWeatherChoice
import com.anopticlabs.gravel.weather.weatherOfferDue
import com.russhwolf.settings.Settings
import coredevices.coreapp.ui.screens.SHOWN_ONBOARDING
import coredevices.pebble.ui.WHATS_NEW_VERSION
import coredevices.pebble.weather.WeatherFetcher
import coredevices.util.CoreConfigHolder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The one-time weather question after the update that added the agreement. */
@Composable
fun WeatherOfferDialog() {
    val settings: Settings = koinInject()
    val configHolder: CoreConfigHolder = koinInject()
    val weatherFetcher: WeatherFetcher = koinInject()
    val config by configHolder.config.collectAsState()
    if (!weatherOfferDue(settings.getBoolean(SHOWN_ONBOARDING, false), config, WHATS_NEW_VERSION)) return

    WeatherShareDialog(interval = config.weatherSyncInterval) { share ->
        configHolder.applyWeatherChoice(share)
        if (share) GlobalScope.launch { weatherFetcher.fetchWeather(this) }
    }
}
