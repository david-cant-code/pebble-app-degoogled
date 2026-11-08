package com.anopticlabs.gravel.weather

import com.anopticlabs.gravel.places.BuiltInPlaces
import com.russhwolf.settings.MapSettings
import coredevices.pebble.services.PebbleWebServices
import coredevices.pebble.weather.YahooWeatherInterceptor
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import io.rebble.libpebblecommon.js.InterceptResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Each new position a watchapp sends is cached, so the cache is capped. */
class YahooLocationCacheTest {

    private val places = BuiltInPlaces { path ->
        when (path.substringAfterLast('/')) {
            "places.tsv" -> "Vienna\t48.208\t16.372\tAT\t09\t1691\n"
            "regions.tsv" -> "AT.09\tVienna\n"
            "countries.tsv" -> "AT\tAustria\n"
            else -> ""
        }.encodeToByteArray()
    }

    private val interceptor = YahooWeatherInterceptor(
        pebbleWebServices = UnusedWebServices,
        geocoder = places.geocoder(),
        coreConfigHolder = CoreConfigHolder(CoreConfig(), MapSettings(), Json),
    )

    private fun request(where: String) = runBlocking {
        val url = "https://query.yahooapis.com/v1/public/yql?q=" +
            "select woeid, name from geo.places where $where".replace(" ", "%20").replace("\"", "%22") + "&format=json"
        interceptor.onIntercepted(url, "GET", null, Uuid.random())
    }

    @Test
    fun theOldestLocationIsDroppedPastTheCap() {
        val count = YahooWeatherInterceptor.MAX_CACHED_LOCATIONS + 1
        repeat(count) { i -> assertNotEquals(InterceptResponse.ERROR, request("text = \"(${10 + i}.5,16.3)\"")) }
        assertEquals(InterceptResponse.ERROR, request("woeid = 1"), "the first location is still cached")
        assertNotEquals(InterceptResponse.ERROR, request("woeid = 2"))
        assertNotEquals(InterceptResponse.ERROR, request("woeid = $count"))
    }

    private object UnusedWebServices : PebbleWebServices by unused()
}

private inline fun <reified T : Any> unused(): T =
    java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${method.name} is not used by location requests")
    } as T
