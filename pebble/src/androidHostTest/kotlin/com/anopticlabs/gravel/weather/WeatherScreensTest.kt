package com.anopticlabs.gravel.weather

import coredevices.pebble.ui.TownSearchNotice
import coredevices.pebble.ui.townSearchNotice
import kotlin.test.Test
import kotlin.test.assertEquals

/** Decisions the town search dialog makes from its state. */
class WeatherScreensTest {

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
