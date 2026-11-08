package com.anopticlabs.gravel.places

import dev.jordond.compass.geocoder.Geocoder
import dev.jordond.compass.geocoder.GeocoderResult
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs against the committed town list, read from the source tree. */
class BuiltInPlacesTest {

    private val reads = mutableListOf<String>()
    private val places = BuiltInPlaces { path ->
        synchronized(reads) { reads += path }
        listFile(path.removePrefix("files/places/")).readBytes()
    }

    @Test
    fun everyFileCreditsGeoNamesAndItsLicense() {
        listOf("places.tsv", "regions.tsv", "countries.tsv").forEach { name ->
            val header = listFile(name).useLines { lines -> lines.takeWhile { it.startsWith('#') }.toList() }
            assertTrue(header.any { "GeoNames (https://www.geonames.org), CC BY 4.0" in it }, "$name: $header")
            assertTrue(header.any { "sha256" in it }, "$name names no source checksum: $header")
        }
    }

    @Test
    fun nearestMatchesABruteForceScanOfTheWholeList() = runBlocking {
        val table = places.table()
        val rows = listFile("places.tsv").readLines().filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.split('\t').let { c -> c[1].toDouble() to c[2].toDouble() } }
        assertTrue(table.size == rows.size && rows.size > 150_000, "rows: ${rows.size}")
        val random = Random(20261101)
        val points = List(100) { random.nextDouble(-90.0, 90.0) to random.nextDouble(-180.0, 180.0) } +
            listOf(90.0 to 0.0, -90.0 to 0.0, 0.0 to 180.0, 0.0 to -180.0, -16.4 to -179.95, 64.0 to -179.99)
        points.forEach { (lat, lon) ->
            val town = checkNotNull(table.nearest(lat, lon))
            val found = PlaceTable.distanceKm(lat, lon, town.latitude, town.longitude)
            val best = rows.minOf { (rowLat, rowLon) -> PlaceTable.distanceKm(lat, lon, rowLat, rowLon) }
            assertEquals(best, found, 1e-9, "nearest to $lat, $lon")
        }
    }

    @Test
    fun knownPlacesResolveToTheirTown() = runBlocking {
        val table = places.table()
        assertEquals("Henrichemont", table.nearest(47.30, 2.52)?.name)
        assertEquals("Portland", table.nearest(45.52, -122.68)?.name)
        assertEquals("Oregon", table.nearest(45.52, -122.68)?.region)
    }

    @Test
    fun searchFindsVillagesAndOtherLanguageNames() = runBlocking {
        val table = places.table()
        assertEquals("Henrichemont", table.search("Henrichemont", 1, typing = true).single().name)
        assertEquals("AT", table.search("Wien", 1, typing = true).single().countryCode)
        assertEquals("Köln", table.search("Cologne", 1, typing = true).single().name)
        assertEquals("Oregon", table.search("portland", 1, typing = true).single().region)
    }

    @Test
    fun searchFindsTheTownTheQueryNames() = runBlocking {
        val table = places.table()
        assertEquals("PT", table.search("porto", 1, typing = true).single().countryCode)
        assertEquals("PK", table.search("islamabad", 1, typing = true).single().countryCode)
        assertEquals("ES", table.search("zaragoza", 1, typing = true).single().countryCode)
        val victoria = table.search("victoria", 1, typing = true).single()
        assertEquals("Victoria" to "HK", victoria.name to victoria.countryCode, "Victoria City outranks Hong Kong, which lists the name as an alternate")
        assertEquals("Aix-en-Provence", table.search("aix en provence", 1, typing = true).single().name)
        assertEquals("Maine", table.search("Portland, ME", 1, typing = true).single().region)
        assertEquals("California", table.search("San Jose CA", 1, typing = true).single().region)
        assertEquals("L'Aquila", table.search("L´Aquila", 1, typing = true).single().name)
        assertEquals(emptyList(), table.search("Fakeville, Kansas", 5, typing = true))
        assertEquals(emptyList(), table.search("San Fakeville", 5, typing = false))
        assertEquals("San Francisco", table.search("san fr", 1, typing = true).single().name)
        assertEquals(emptyList(), table.search("Pearl District, Portland, OR", 1, typing = false))
        assertEquals("L'Aquila", table.search("L′Aquila", 1, typing = true).single().name)
        val modifierApostrophe = "Mar" + Char(0x02BC) + "yinka"
        assertEquals("Mar'yinka", table.search(modifierApostrophe, 1, typing = true).single().name)
        assertEquals("Mar'yinka", table.search(modifierApostrophe, 1, typing = false).single().name)
        assertEquals("Smith's Parish", table.search("Smith's Parish Smith's", 1, typing = false).single().name, "region Smith\u02BCs")
        val yahoo = "mission district, san francisco, california, 90110, united states"
        assertEquals("Mission District", table.search(yahoo, 1, typing = true).single().name)
        assertEquals(emptyList(), table.search("Mision District, San Francisco, CA", 1, typing = false), "a later part is never the town")
    }

    @Test
    fun aWatchappLookupFindsCityRegionAndCountryForms() = runBlocking {
        val table = places.table()
        fun first(query: String) = table.search(query, 1, typing = false).singleOrNull()
        assertEquals("GB", first("London UK")?.countryCode)
        assertEquals("British Columbia", first("Vancouver BC")?.region)
        assertEquals("Texas", first("Austin TX USA")?.region)
        assertEquals("New York", first("Albany New York")?.region)
        assertEquals("Nova Scotia", first("Halifax Nova Scotia")?.region)
        assertEquals("ZA", first("Cape Town South Africa")?.countryCode)
        assertEquals("AE", first("Dubai UAE")?.countryCode)
        assertEquals("Western Australia", first("Perth WA")?.region)
        assertEquals("Quebec", first("Montreal QC")?.region)
        assertEquals("San Francisco", first("San Francisco CA 94110")?.name)
        assertEquals("Oregon", first("Portland 97201")?.region)
        assertEquals("Oregon", first("Portland, OR 97201")?.region)
        assertEquals("Washington", first("Seattle, WA 98101")?.region)
        assertEquals("NL", first("Amsterdam Netherlands")?.countryCode)
        assertEquals("CZ", first("Prague Czech Republic")?.countryCode)
        assertEquals("Porto Alegre", first("Porto Alegre Rio Grande do Sul")?.name)
        val formerAndOtherNames = mapOf(
            "Kinshasa DRC" to "CD", "Kinshasa DR Congo" to "CD", "Brazzaville Congo" to "CG", "Yangon Burma" to "MM",
            "Praia Cape Verde" to "CV", "Skopje Macedonia" to "MK", "Mbabane Swaziland" to "SZ",
            "Dili East Timor" to "TL", "Istanbul Turkiye" to "TR", "Hanoi Viet Nam" to "VN",
        )
        formerAndOtherNames.forEach { (query, country) -> assertEquals(country, first(query)?.countryCode, query) }
        assertEquals("Palmerston North", first("Palmerston North, NZ")?.name)
        assertEquals(null, first("Fakeville, Kansas"))
        assertEquals(null, first("Fakeville, California, USA"))
    }

    @Test
    fun aWatchappLookupDoesNotGuessButTheSearchScreenDoes() = runBlocking {
        assertEquals(emptyList(), places.forward("San Fakeville"))
        assertTrue(places.search("San Fakeville").isNotEmpty())
    }

    @Test
    fun theListIsReadOnceAcrossConcurrentCallers() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val waiting = BuiltInPlaces { path ->
            synchronized(reads) { reads += path }
            release.await()
            listFile(path.removePrefix("files/places/")).readBytes()
        }
        val callers = List(8) { async(Dispatchers.Default) { waiting.reverse(48.2, 16.37) } }
        withTimeout(5_000) { while (synchronized(reads) { reads.isEmpty() }) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(200)
        release.complete(Unit)
        callers.awaitAll()
        assertEquals(listOf("countries.tsv", "fold.tsv", "places.tsv", "regions.tsv"), reads.map { it.substringAfterLast('/') }.sorted())
    }

    @Test
    fun aCallerCanceledWhileTheListLoadsLeavesItLoaded() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = BuiltInPlaces { path ->
            synchronized(reads) { reads += path }
            // Blocks without suspending, so the cancel arrives while the load is under way.
            if (path.endsWith("fold.tsv")) {
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            listFile(path.removePrefix("files/places/")).readBytes()
        }
        val caller = launch(Dispatchers.Default) { blocking.table() }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        caller.cancel()
        release.countDown()
        caller.join()
        blocking.table()
        assertEquals(4, reads.size)
    }

    /**
     * The read and parse leave the caller's thread. A change that kept the reads on the
     * dispatcher but moved only the parse back to the caller would still pass.
     */
    @Test
    fun theListIsReadOffTheCallersThread() = runBlocking {
        val threads = mutableSetOf<Thread>()
        val recording = BuiltInPlaces { path ->
            synchronized(threads) { threads += Thread.currentThread() }
            listFile(path.removePrefix("files/places/")).readBytes()
        }
        recording.table()
        assertTrue(threads.isNotEmpty() && Thread.currentThread() !in threads, "$threads")
    }

    @Test
    fun anErrorDuringTheLoadIsTriedAgain() = runBlocking {
        var attempts = 0
        val outOfMemoryOnce = BuiltInPlaces { path ->
            if (attempts++ == 0) throw OutOfMemoryError("test")
            listFile(path.removePrefix("files/places/")).readBytes()
        }
        assertFailsWith<OutOfMemoryError> { outOfMemoryOnce.table() }
        assertEquals("Vienna", outOfMemoryOnce.table().nearest(48.2, 16.37)?.name)
    }

    @Test
    fun theSearchScreenGetsResultsForThreeLetters() = runBlocking {
        val result = places.autocomplete().search("ulm")
        assertEquals("Ulm", result.getOrNull().orEmpty().first().locality)
        assertEquals(BuiltInPlaces.MAX_RESULTS, places.autocomplete().search("san").getOrNull().orEmpty().size)
        assertEquals(5, BuiltInPlaces.MAX_RESULTS, "the search dialog does not scroll")
        assertTrue(places.geocoder().platformGeocoder === places)
    }

    @Test
    fun aFailedLoadIsNotRetried() = runBlocking {
        var attempts = 0
        val broken = BuiltInPlaces { attempts++; throw IOException("missing") }
        repeat(3) { assertTrue(Geocoder(broken).reverse(48.2, 16.37) is GeocoderResult.GeocodeFailed) }
        assertEquals(1, attempts)
    }

    @Test
    fun compassGetsTheTownAsAPlace() = runBlocking {
        val place = Geocoder(places).reverse(48.2, 16.37).getOrNull().orEmpty().single()
        assertEquals("Vienna", place.locality)
        assertEquals("Vienna", place.administrativeArea)
        assertEquals("AT", place.isoCountryCode)
        assertEquals("Austria", place.country)
        val coordinates = Geocoder(places).forward("Henrichemont").getOrNull().orEmpty().first()
        assertEquals(47.303, coordinates.latitude, 1e-9)
    }

    @Test
    fun aListThatCannotBeParsedIsAFailedLookupNotBadCoordinates() = runBlocking {
        val broken = BuiltInPlaces { "Vienna\tnorth\t16.3\tAT\t09\t1\n".encodeToByteArray() }
        val result = Geocoder(broken).reverse(48.2, 16.37)
        assertTrue(result is GeocoderResult.GeocodeFailed, "$result")
    }

    private fun listFile(name: String): File {
        val relative = "src/commonMain/composeResources/files/places/$name"
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, relative), File(it, "pebble/$relative")) }
            .firstOrNull { it.isFile }
            ?: error("$relative not found above ${File("").absolutePath}")
    }
}
