package com.anopticlabs.gravel.places

import co.touchlab.kermit.Logger
import coreapp.pebble.generated.resources.Res
import dev.jordond.compass.Coordinates
import dev.jordond.compass.Place
import dev.jordond.compass.autocomplete.Autocomplete
import dev.jordond.compass.autocomplete.AutocompleteOptions
import dev.jordond.compass.autocomplete.AutocompleteService
import dev.jordond.compass.geocoder.Geocoder
import dev.jordond.compass.geocoder.PlatformGeocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gravel's geocoder: answers compass's place lookups from the built-in town list, with no
 * network call. The list is read and parsed on first use and then kept. A list that cannot be
 * read or parsed (an exception) fails every later lookup without another attempt; an Error such
 * as running out of memory is not kept, and the next lookup tries again.
 */
class BuiltInPlaces(
    private val readFile: suspend (path: String) -> ByteArray = { Res.readBytes(it) },
) : PlatformGeocoder, AutocompleteService<Place> {
    private val mutex = Mutex()
    private var loaded: Result<PlaceTable>? = null

    suspend fun table(): PlaceTable = mutex.withLock {
        val result = loaded ?: load()
        // Rethrown as IllegalStateException: compass reports an IllegalArgumentException
        // as invalid coordinates (compass 3.2.0, DefaultGeocoder.handleResult).
        result.getOrElse { throw IllegalStateException("built-in town list unreadable", it) }
    }

    // The parse is CPU-bound, and callers include the main thread (the weather screen). The
    // result is stored inside the block: withContext discards it for a caller canceled meanwhile
    // (kotlinx.coroutines 1.11.0, Builders.common.kt withContext).
    private suspend fun load(): Result<PlaceTable> = withContext(Dispatchers.Default) {
        try {
            Result.success(
                PlaceTable.parse(
                    places = readFile("$DIRECTORY/places.tsv").decodeToString(),
                    regions = readFile("$DIRECTORY/regions.tsv").decodeToString(),
                    countries = readFile("$DIRECTORY/countries.tsv").decodeToString(),
                    folds = readFile("$DIRECTORY/fold.tsv").decodeToString(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "built-in town list unreadable" }
            Result.failure(e)
        }.also { loaded = it }
    }

    override fun isAvailable(): Boolean = true

    // Strict: a watchapp's lookup uses the first result, so a guess is worse than nothing.
    override suspend fun forward(address: String): List<Coordinates> =
        table().search(address, MAX_RESULTS, typing = false).map { Coordinates(it.latitude, it.longitude) }

    override suspend fun reverse(latitude: Double, longitude: Double): List<Place> =
        listOfNotNull(table().nearest(latitude, longitude)?.toPlace())

    override suspend fun search(query: String): List<Place> =
        table().search(query, MAX_RESULTS, typing = true).map { it.toPlace() }

    fun geocoder(): Geocoder = Geocoder(this)

    // minimumQuery 2: compass returns no results for a query no longer than it (compass 3.2.0,
    // DefaultAutocomplete.search), and the weather search starts at 3 characters.
    fun autocomplete(): Autocomplete<Place> = Autocomplete(this, AutocompleteOptions(minimumQuery = 2))

    companion object {
        /** As many as upstream's geocoder asked for (compass 3.2.0, AndroidPlatformGeocoder.forward); the search dialog does not scroll. */
        const val MAX_RESULTS = 5
        private const val DIRECTORY = "files/places"
        private val logger = Logger.withTag("BuiltInPlaces")
    }
}

/** The town as compass's place: callers read [Place.locality] for the name. */
fun Town.toPlace(): Place = Place(
    coordinates = Coordinates(latitude, longitude),
    name = name,
    street = null,
    isoCountryCode = countryCode,
    country = country,
    postalCode = null,
    administrativeArea = region,
    subAdministrativeArea = null,
    locality = name,
    subLocality = null,
    thoroughfare = null,
    subThoroughfare = null,
)
