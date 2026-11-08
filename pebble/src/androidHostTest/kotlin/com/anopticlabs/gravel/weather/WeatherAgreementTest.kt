package com.anopticlabs.gravel.weather

import com.anopticlabs.gravel.places.BuiltInPlaces
import com.anopticlabs.gravel.places.Town
import com.russhwolf.settings.MapSettings
import coredevices.database.WeatherLocationDao
import coredevices.database.WeatherLocationEntity
import coredevices.pebble.services.PebbleWebServices
import coredevices.pebble.weather.WeatherFetcher
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.CoreConfigHolder
import coredevices.util.weatherOn
import coredevices.util.withWeatherOff
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class WeatherAgreementTest {

    private val vienna = Town("Vienna", 48.208, 16.372, "AT", "Austria", "Vienna", 1691)
    private val current = WeatherLocationEntity(Uuid.random(), 0, "Current Location", null, null, true)
    private val phone = GeolocationPositionResult.Success(Clock.System.now(), 48.2123, 16.3901, 10.0, null, null, null)

    // currentLocationAsTown

    @Test
    fun theTownsPositionReplacesThePhones() = runBlocking {
        val row = currentLocationAsTown(current, allowed = true, position = { phone }, townNear = { _, _ -> vienna }, isTown = { _, _ -> false })
        assertEquals(Triple(48.208, 16.372, "Vienna"), Triple(row.latitude, row.longitude, row.name))
    }

    @Test
    fun withoutLocationAccessTheRowIsForgottenAndThePhoneNotAsked() = runBlocking {
        val stored = current.copy(latitude = 48.208, longitude = 16.372, name = "Vienna")
        var asked = false
        val row = currentLocationAsTown(stored, allowed = false, position = { asked = true; phone }, townNear = { _, _ -> vienna }, isTown = { _, _ -> true })
        assertEquals(Triple(null, null, "Current Location"), Triple(row.latitude, row.longitude, row.name))
        assertFalse(asked)
    }

    private val noFix: suspend () -> GeolocationPositionResult = { GeolocationPositionResult.Error("no fix") }

    @Test
    fun withNoFixAnOlderBuildsPositionIsSentAsATown() = runBlocking {
        // An exact position stored by a build from before this one is never sent as it is.
        val stored = current.copy(latitude = 48.2123, longitude = 16.3901)
        val row = currentLocationAsTown(stored, true, noFix, { _, _ -> vienna }, { _, _ -> false })
        assertEquals(48.208 to 16.372, row.latitude to row.longitude)
        val empty = currentLocationAsTown(current, true, noFix, { _, _ -> vienna }, { _, _ -> false })
        assertEquals(current, empty)
    }

    @Test
    fun withNoFixAStoredTownIsKept() = runBlocking {
        val stored = current.copy(latitude = 48.212, longitude = 16.392, name = "Landstraße")
        var picked = false
        val row = currentLocationAsTown(stored, true, noFix, { _, _ -> picked = true; vienna }, { _, _ -> true })
        assertEquals(stored, row)
        assertFalse(picked)
    }

    @Test
    fun withNoTownTheRowIsForgotten() = runBlocking {
        val stored = current.copy(latitude = 48.208, longitude = 16.372, name = "Vienna")
        val none = currentLocationAsTown(stored, true, { phone }, { _, _ -> null }, { _, _ -> false })
        val broken = currentLocationAsTown(stored, true, { phone }, { _, _ -> throw IllegalStateException("unreadable") }, { _, _ -> false })
        val brokenNoFix = currentLocationAsTown(stored, true, noFix, { _, _ -> vienna }, { _, _ -> throw IllegalStateException("unreadable") })
        listOf(none, broken, brokenNoFix).forEach { assertEquals(Triple(null, null, "Current Location"), Triple(it.latitude, it.longitude, it.name)) }
    }

    // The agreement and the one-time offer

    @Test
    fun aConfigStoredBeforeTheAgreementHasWeatherOff() {
        val old = Json.decodeFromString<CoreConfig>("""{"weatherPinsV2":true}""")
        assertTrue(old.fetchWeather)
        assertFalse(old.weatherAgreed)
        assertFalse(old.weatherOn)
        assertFalse(CoreConfig().weatherOn, "a new install starts with weather off")
    }

    @Test
    fun theOfferWaitsForSetupAndWhatsNewAndShowsOnce() {
        val due = CoreConfig(lastSeenWhatsNewVersion = 14)
        assertTrue(weatherOfferDue(onboarded = true, config = due, whatsNewVersion = 14))
        assertFalse(weatherOfferDue(onboarded = false, config = due, whatsNewVersion = 14))
        assertFalse(weatherOfferDue(true, due, whatsNewVersion = 15), "What's New is still to be shown")
        assertFalse(weatherOfferDue(true, due.copy(weatherAgreed = true), 14), "agreed")
        assertFalse(weatherOfferDue(true, due.copy(fetchWeather = false), 14), "switched off, or answered off")
    }

    @Test
    fun eitherAnswerEndsTheOffer() {
        listOf(false, true).forEach { share ->
            val holder = CoreConfigHolder(CoreConfig(lastSeenWhatsNewVersion = 14), MapSettings(), Json)
            assertTrue(weatherOfferDue(true, holder.config.value, 14), "due before the choice")
            holder.applyWeatherChoice(share)
            assertEquals(share, holder.config.value.weatherOn)
            assertFalse(weatherOfferDue(true, holder.config.value, 14), "share = $share")
        }
    }

    @Test
    fun answeringOffWithdrawsAnEarlierAgreement() {
        val holder = CoreConfigHolder(CoreConfig(), MapSettings(), Json)
        holder.applyWeatherChoice(true)
        holder.applyWeatherChoice(false)
        assertFalse(holder.config.value.weatherAgreed)
        assertFalse(holder.config.value.fetchWeather)
    }

    @Test
    fun theTextNamesTheIntervalInUse() {
        assertTrue("about once an hour" in weatherConsentText(1.hours))
        assertTrue("about every 15 minutes" in weatherConsentText(15.minutes))
        assertTrue("about every 6 hours" in weatherConsentText(6.hours))
        assertTrue("about every 2 hours" in weatherConsentText(2.hours), "an interval the menu does not list")
    }

    @Test
    fun theTextNamesTheTownRadiusInUse() {
        assertTrue("within ${CURRENT_LOCATION_RADIUS_KM.toInt()} km of your phone" in weatherConsentText(1.hours))
    }

    // fetchWeather end to end

    private class Run(config: CoreConfig, allowed: Boolean, rows: List<WeatherLocationEntity>) {
        val sent = mutableListOf<Pair<Double, Double>>()
        val dao = MemoryDao(rows)
        val config = MutableStateFlow(config)
        val watch = RecordingLibPebble()
        val settings = MapSettings()
        var positionsAsked = 0
        var fix = true
        var positionGate: CompletableDeferred<Unit>? = null
        val positionAsked = CompletableDeferred<Unit>()
        private val places = BuiltInPlaces { path ->
            when (path.substringAfterLast('/')) {
                // Landstraße is nearest the phone's position below, Vienna the most populous within 10 km.
                "places.tsv" -> "Vienna\t48.208\t16.372\tAT\t09\t1691\nLandstraße\t48.212\t16.392\tAT\t09\t90\n" +
                    "Portland\t45.523\t-122.676\tUS\tOR\t652\n"
                else -> ""
            }.encodeToByteArray()
        }
        private val webServices = java.lang.reflect.Proxy.newProxyInstance(
            PebbleWebServices::class.java.classLoader, arrayOf(PebbleWebServices::class.java),
        ) { _, method, args ->
            check(method.name == "getWeather") { "${method.name} is not used by a weather fetch" }
            synchronized(sent) { sent += (args[0] as Double) to (args[1] as Double) }
            null
        } as PebbleWebServices
        private val geolocation = object : SystemGeolocation {
            override suspend fun getCurrentPosition(maximumAge: Duration?, timeout: Duration?, highAccuracy: Boolean): GeolocationPositionResult {
                positionsAsked++
                positionAsked.complete(Unit)
                positionGate?.await()
                if (!fix) return GeolocationPositionResult.Error("no fix")
                return GeolocationPositionResult.Success(Clock.System.now(), 48.2123, 16.3901, 10.0, null, null, null)
            }
            override suspend fun watchPosition(interval: Duration, highAccuracy: Boolean): Flow<GeolocationPositionResult> = emptyFlow()
        }
        val fetcher = WeatherFetcher(
            geolocation, CoreConfigFlow(this.config), webServices, watch, Clock.System,
            settings, dao, places, LocationAccess { allowed },
        )
    }

    private class RecordingLibPebble(fake: FakeLibPebble = FakeLibPebble()) : LibPebble by fake {
        val weatherUpdates = mutableListOf<List<WeatherLocationData>>()
        override fun updateWeatherData(weatherData: List<WeatherLocationData>) {
            synchronized(weatherUpdates) { weatherUpdates += weatherData }
        }
    }

    private val fixed = WeatherLocationEntity(Uuid.random(), 1, "Portland", 45.5, -122.6, false)
    private val agreed = CoreConfig(fetchWeather = true, weatherAgreed = true)
    private val exact = current.copy(latitude = 48.2123, longitude = 16.3901, name = "Wien Landstrasse")

    @Test
    fun withoutTheAgreementNothingIsSentAndThePositionIsForgotten() = runBlocking {
        val run = Run(CoreConfig(fetchWeather = true, weatherAgreed = false), allowed = true, rows = listOf(exact, fixed))
        run.fetcher.fetchWeather(this)
        run.fetcher.fetchIfNotFetchedYet()
        assertEquals(emptyList(), run.sent)
        assertEquals(0, run.positionsAsked)
        assertEquals(Triple(null, null, "Current Location"), run.dao.rows.single { it.currentLocation }.let { Triple(it.latitude, it.longitude, it.name) })
        assertTrue(run.watch.weatherUpdates.isNotEmpty() && run.watch.weatherUpdates.all { it.isEmpty() })
    }

    @Test
    fun startingWithWeatherOffClearsAtOnce() = runBlocking {
        val run = Run(CoreConfig(fetchWeather = true, weatherAgreed = false), allowed = true, rows = listOf(exact))
        // An install updated from a build that had already synced weather.
        run.settings.putBoolean("has_done_one_weather_sync", true)
        run.fetcher.init()
        assertEquals(null, run.dao.rows.single().latitude)
        assertEquals(listOf(emptyList()), run.watch.weatherUpdates)
        assertEquals(emptyList(), run.sent)
    }

    @Test
    fun agreedSendsTheTownForCurrentLocationAndFixedPlacesAsEntered() = runBlocking {
        val run = Run(agreed, allowed = true, rows = listOf(current, fixed))
        run.fetcher.fetchWeather(this)
        assertEquals(setOf(48.208 to 16.372, 45.5 to -122.6), run.sent.toSet())
        assertEquals(48.208 to 16.372, run.dao.rows.single { it.currentLocation }.let { it.latitude to it.longitude })
    }

    @Test
    fun withNoFixTheStoredTownIsSentAndAnOlderBuildsPositionSnapped() = runBlocking {
        val town = current.copy(latitude = 48.212, longitude = 16.392, name = "Landstraße")
        val keeps = Run(agreed, allowed = true, rows = listOf(town)).apply { fix = false }
        keeps.fetcher.fetchWeather(this)
        assertEquals(listOf(48.212 to 16.392), keeps.sent)
        assertEquals(town, keeps.dao.rows.single())
        val older = Run(agreed, allowed = true, rows = listOf(exact)).apply { fix = false }
        older.fetcher.fetchWeather(this)
        assertEquals(listOf(48.208 to 16.372), older.sent)
    }

    @Test
    fun revokedSendsOnlyFixedPlacesAndTheWatchDropsCurrentLocation() = runBlocking {
        val stored = current.copy(latitude = 48.208, longitude = 16.372, name = "Vienna")
        val run = Run(agreed, allowed = false, rows = listOf(stored, fixed))
        run.fetcher.fetchWeather(this)
        assertEquals(listOf(45.5 to -122.6), run.sent)
        assertEquals(Triple(null, null, "Current Location"), run.dao.rows.single { it.currentLocation }.let { Triple(it.latitude, it.longitude, it.name) })
        assertEquals(listOf(fixed.key), run.watch.weatherUpdates.last().map { it.key })
    }

    @Test
    fun aFetchUnderWayWhenWeatherIsSwitchedOffSendsNothing() = runBlocking {
        val run = Run(agreed, allowed = true, rows = listOf(current, fixed))
        run.positionGate = CompletableDeferred()
        val fetch = launch(Dispatchers.Default) { run.fetcher.fetchWeather(this) }
        withTimeout(5_000) { run.positionAsked.await() }
        run.config.value = agreed.withWeatherOff()
        run.positionGate!!.complete(Unit)
        fetch.join()
        assertEquals(emptyList(), run.sent)
        assertEquals(emptyList(), run.watch.weatherUpdates.last())
        assertEquals(null, run.dao.rows.single { it.currentLocation }.latitude)
    }

    @Test
    fun weatherIsOnOnlyWithBothFlags() {
        assertFalse(CoreConfig(fetchWeather = false, weatherAgreed = true).weatherOn)
        assertFalse(CoreConfig(fetchWeather = true, weatherAgreed = false).weatherOn)
        assertTrue(agreed.weatherOn)
        assertEquals(CoreConfig(fetchWeather = false, weatherAgreed = false), agreed.withWeatherOff())
    }

    @Test
    fun onlyAgreeingSetsTheAgreement() {
        val holder = CoreConfigHolder(CoreConfig(), MapSettings(), Json)
        holder.update(holder.config.value.copy(weatherAgreed = true))
        assertFalse(holder.config.value.weatherAgreed, "a plain update cannot agree")
        holder.agreeToWeather()
        assertTrue(holder.config.value.weatherOn)
        val stale = holder.config.value
        holder.update(holder.config.value.withWeatherOff())
        holder.update(stale.copy(weatherPinsV2 = false))
        assertFalse(holder.config.value.weatherAgreed, "an older copy does not bring the agreement back")
        assertFalse(holder.config.value.fetchWeather, "nor switch weather back on")
        assertFalse(holder.config.value.weatherPinsV2)
    }

    private class MemoryDao(initial: List<WeatherLocationEntity>) : WeatherLocationDao {
        val rows = initial.toMutableList()
        override suspend fun upsert(location: WeatherLocationEntity) {
            rows.removeAll { it.key == location.key }
            rows += location
        }
        override suspend fun delete(location: WeatherLocationEntity) { rows.removeAll { it.key == location.key } }
        override suspend fun updateOrder(key: Uuid, newIndex: Int) = Unit
        override fun getAllLocationsFlow(): Flow<List<WeatherLocationEntity>> = emptyFlow()
        override suspend fun getAllLocations(): List<WeatherLocationEntity> = rows.sortedBy { it.orderIndex }
    }
}
