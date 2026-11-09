package com.anopticlabs.gravel.weather

import com.russhwolf.settings.MapSettings
import coredevices.pebble.ui.BacklightTimeControls
import coredevices.pebble.ui.TownSearchNotice
import coredevices.pebble.ui.backlightTimeControls
import coredevices.pebble.ui.setBacklightTimeByHand
import coredevices.pebble.ui.townSearchNotice
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

/** Decisions the town search dialog and the backlight setting make from their state. */
class WeatherScreensTest {

    @Test
    fun withWeatherOffTheBacklightOffersOnlyTheTimePickers() {
        val automatic = CoreConfig(backlightColorAutoSunriseSunset = true)
        assertEquals(BacklightTimeControls(automaticToggle = false, pickers = true), backlightTimeControls(automatic))
        val agreed = automatic.copy(fetchWeather = true, weatherAgreed = true)
        assertEquals(BacklightTimeControls(automaticToggle = true, pickers = false), backlightTimeControls(agreed))
        val byHand = agreed.copy(backlightColorAutoSunriseSunset = false)
        assertEquals(BacklightTimeControls(automaticToggle = true, pickers = true), backlightTimeControls(byHand))
    }

    @Test
    fun aTimeSetByHandTurnsTheAutomaticTimesOff() {
        val holder = CoreConfigHolder(CoreConfig(backlightColorAutoSunriseSunset = true), MapSettings(), Json)
        holder.setBacklightTimeByHand()
        assertFalse(holder.config.value.backlightColorAutoSunriseSunset)
    }

    @Test
    fun noTownFoundShowsOnlyOnceTheSearchForWhatIsTypedHasFinished() {
        assertEquals(TownSearchNotice.None, townSearchNotice(false, false, searchedFor = null, typed = ""), "before a search")
        assertEquals(TownSearchNotice.None, townSearchNotice(false, false, searchedFor = null, typed = "Fakev"), "first search running")
        assertEquals(TownSearchNotice.None, townSearchNotice(false, false, searchedFor = "Fake", typed = "Fakev"), "next search running")
        assertEquals(TownSearchNotice.NoTownFound, townSearchNotice(false, false, searchedFor = "Fakev ", typed = "Fakev "))
        assertEquals(TownSearchNotice.None, townSearchNotice(true, false, searchedFor = "Portland", typed = "Portland"))
    }

    @Test
    fun aListThatCannotBeOpenedSaysSo() {
        assertEquals(TownSearchNotice.ListUnreadable, townSearchNotice(false, true, searchedFor = "Portland", typed = "Portland"))
    }
}
