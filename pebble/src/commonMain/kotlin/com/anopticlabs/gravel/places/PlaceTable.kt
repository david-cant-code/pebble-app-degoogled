package com.anopticlabs.gravel.places

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place from the built-in town list. [population] is in thousands, rounded. */
data class Town(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val countryCode: String,
    val country: String?,
    val region: String?,
    val population: Int,
)

/**
 * The built-in town list (GeoNames, trimmed by tools/place-table/make_place_table.py), answering
 * nearest-town and name lookups on the phone. Rows are held in parallel arrays and the search
 * keys in one string to keep the loaded list small.
 */
class PlaceTable private constructor(
    private val displayNames: String,
    private val nameStarts: IntArray,
    private val latitudes: IntArray,
    private val longitudes: IntArray,
    private val countryIndex: ShortArray,
    private val regionIndex: IntArray,
    private val populations: IntArray,
    private val countries: List<Area>,
    private val countryCodes: List<String>,
    private val regions: List<Area?>,
    private val keys: String,
    private val keyStarts: IntArray,
    private val folds: Map<Char, String>,
    private val cellStarts: IntArray,
    private val cellRows: IntArray,
) {
    /** A country or region: display name, its name as a search key, and the codes that name it. */
    private class Area(val name: String?, val nameKey: String, val codeKeys: Set<String>)

    val size: Int get() = latitudes.size

    /**
     * The listed place nearest to the position, however far, the larger one where two are equally
     * near; null only for an empty list.
     */
    fun nearest(latitude: Double, longitude: Double): Town? {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "not a position: $latitude, $longitude" }
        if (size == 0) return null
        var radiusKm = FIRST_RADIUS_KM
        while (true) {
            var best = -1
            var bestKm = Double.MAX_VALUE
            forEachRowNear(latitude, longitude, radiusKm) { row ->
                val km = distanceKm(latitude, longitude, latitudes[row] / SCALE, longitudes[row] / SCALE)
                if (km < bestKm || (km == bestKm && outranks(row, best))) {
                    bestKm = km
                    best = row
                }
            }
            // The box searched holds every point within radiusKm, so a hit within radiusKm is the nearest.
            if (best >= 0 && (bestKm <= radiusKm || radiusKm >= HALF_CIRCUMFERENCE_KM)) return town(best)
            radiusKm *= 2
        }
    }

    /**
     * The most populous listed place within [radiusKm] of the position, by [Town.population] in
     * thousands, and of equally populous ones the first in the list's order; with none that
     * close, [nearest].
     */
    fun largestWithin(latitude: Double, longitude: Double, radiusKm: Double): Town? {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "not a position: $latitude, $longitude" }
        var best = -1
        forEachRowNear(latitude, longitude, radiusKm) { row ->
            val within = distanceKm(latitude, longitude, latitudes[row] / SCALE, longitudes[row] / SCALE) <= radiusKm
            val better = best < 0 || populations[row] > populations[best] ||
                (populations[row] == populations[best] && row < best)
            if (within && better) best = row
        }
        return if (best >= 0) town(best) else nearest(latitude, longitude)
    }

    /**
     * Places for a place name, ignoring case, accents and punctuation. The name is the first comma
     * part: its exact matches (name or alternate name), or else those of its leading words whose
     * region or country every other word that is not a number names, alone or in a phrase
     * ("Portland OR", "Halifax Nova Scotia", "Portland 97201"). Later comma parts only rank
     * ("Portland, OR"). With [typing] false (a watchapp's lookup, whose first result is used) the
     * result is those exact matches, or nothing. With [typing] true (the search screen) the other
     * places with a word starting with the same words follow them; with no exact match it offers
     * places with a word starting with the first part, then the exact matches of the longest
     * leading words that have any. See [strength] for what names a region or country. Each list
     * ranks exact matches first, then the highest summed [strength] over the other words, then
     * exact names (a place whose alternate name is exact counts only if [ALTERNATE_WEIGHT] times
     * bigger), names, alternate names starting with the query, other word matches, each by
     * population. Only the first [MAX_QUERY_CHARS] characters, [MAX_PARTS] parts and [MAX_WORDS]
     * words of a part are read.
     */
    fun search(query: String, limit: Int, typing: Boolean): List<Town> {
        if (limit <= 0) return emptyList()
        val parts = query.take(MAX_QUERY_CHARS).split(',').map { searchKey(it) }.filter { it.isNotEmpty() }
            .take(MAX_PARTS).map { it.split(' ').take(MAX_WORDS) }
        if (parts.isEmpty()) return emptyList()
        val words = parts[0]
        val later = parts.drop(1).flatMap { phrases(joinInitials(it)) }
        val memo = HashMap<String, Map<Int, Int>>()
        fun matchesOf(kept: Int) = memo.getOrPut(words.subList(0, kept).joinToString(" ")) {
            matches(words.subList(0, kept).joinToString(" "))
        }
        fun Map<Int, Int>.hasExact() = values.any { it <= RANK_EXACT_ALTERNATE }

        val whole = matchesOf(words.size)
        if (whole.hasExact()) return ranked(if (typing) whole else whole.filterValues { it <= RANK_EXACT_ALTERNATE }, later, limit)
        for (kept in words.size - 1 downTo 1) {
            val dropped = joinInitials(words.subList(kept, words.size))
            val placedRows = placedRows(matchesOf(kept), dropped)
            if (placedRows.isNotEmpty()) return ranked(if (typing) matchesOf(kept) else placedRows, phrases(dropped) + later, limit)
        }
        if (!typing) return emptyList()
        val typed = ranked(whole, later, limit)
        val kept = (words.size - 1 downTo 1).firstOrNull { matchesOf(it).hasExact() } ?: return typed
        val shorter = ranked(matchesOf(kept), phrases(joinInitials(words.subList(kept, words.size))) + later, limit)
        return (typed + shorter).distinct().take(limit)
    }

    // The exact matches whose region or country the dropped words, numbers aside, name.
    private fun placedRows(ranks: Map<Int, Int>, dropped: List<String>): Map<Int, Int> {
        val words = dropped.filterNot { word -> word.all { it.isDigit() } }
        return ranks.filter { (row, rank) -> rank <= RANK_EXACT_ALTERNATE && (words.isEmpty() || covered(row, words)) }
    }

    // Every word, alone or in a phrase of any length, names the place's region or country.
    private fun covered(row: Int, words: List<String>): Boolean {
        var i = 0
        while (i < words.size) {
            val step = (words.size - i downTo 1).firstOrNull { length ->
                strength(row, words.subList(i, i + length).joinToString(" ")) > 0
            } ?: return false
            i += step
        }
        return true
    }

    /** The words, numbers aside, alone and as phrases, for ranking. */
    private fun phrases(words: List<String>): List<String> {
        val named = words.filterNot { word -> word.all { it.isDigit() } }
        return (1..named.size).flatMap { length -> named.windowed(length) { it.joinToString(" ") } }
    }

    private fun ranked(ranks: Map<Int, Int>, qualifiers: List<String>, limit: Int): List<Town> {
        val inQualifiedArea = ranks.keys.associateWith { row -> qualifiers.sumOf { strength(row, it) } }
        return ranks.entries
            .sortedWith(
                compareBy<Map.Entry<Int, Int>> { it.value > RANK_EXACT_ALTERNATE }
                    .thenByDescending { inQualifiedArea.getValue(it.key) }
                    .thenBy { if (it.value == RANK_EXACT_ALTERNATE) RANK_EXACT_NAME else it.value }
                    .thenByDescending {
                        val population = populations[it.key].toDouble()
                        if (it.value == RANK_EXACT_ALTERNATE) population / ALTERNATE_WEIGHT else population
                    }
                    .thenBy { it.value }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { town(it.key) }
    }

    /** Lowercase, accents and apostrophes removed, other punctuation as single spaces. */
    fun searchKey(text: String): String = searchKey(folds, text)

    private fun matches(needle: String): Map<Int, Int> {
        val ranks = HashMap<Int, Int>()
        var from = 0
        while (true) {
            val at = keys.indexOf(needle, from)
            if (at < 0) break
            from = at + 1
            val before = if (at == 0) '\n' else keys[at - 1]
            if (before != '\n' && before != '|' && before != ' ') continue
            val row = rowOfKey(at)
            val end = at + needle.length
            val whole = before == '\n' || before == '|'
            val rank = when {
                whole && (end == keys.length || keys[end] == '\n' || keys[end] == '|') ->
                    if (at == keyStarts[row]) RANK_EXACT_NAME else RANK_EXACT_ALTERNATE
                at == keyStarts[row] -> RANK_NAME_PREFIX
                whole -> RANK_ALTERNATE_PREFIX
                else -> RANK_WORD
            }
            val previous = ranks[row]
            if (previous == null || rank < previous) ranks[row] = rank
        }
        return ranks
    }

    /**
     * How strongly [qualifier] names the place's area: 4 for its region's code, postal abbreviation
     * or name, 3 for its country's code (ISO alpha-2 or alpha-3, or one the generator adds) or name,
     * 2 for the start of its region's name and 1 for the start of its country's name (three
     * characters or more); 0 otherwise. A qualifier naming the country scores 3 even where the
     * region has the same name (the state of Mexico), so it does not favor that region's places.
     */
    private fun strength(row: Int, qualifier: String): Int {
        val region = regions[regionIndex[row]]
        val country = countries[countryIndex[row].toInt()]
        fun exact(area: Area) = qualifier in area.codeKeys || area.nameKey == qualifier
        fun prefix(area: Area) = qualifier.length >= 3 && area.nameKey.startsWith(qualifier)
        return when {
            exact(country) -> 3
            region != null && exact(region) -> 4
            region != null && prefix(region) -> 2
            prefix(country) -> 1
            else -> 0
        }
    }

    private fun outranks(row: Int, other: Int): Boolean =
        other < 0 || populations[row] > populations[other] || (populations[row] == populations[other] && row < other)

    private fun town(row: Int): Town {
        val country = countries[countryIndex[row].toInt()]
        return Town(
            name = displayNames.substring(nameStarts[row], nameStarts[row + 1]),
            latitude = latitudes[row] / SCALE,
            longitude = longitudes[row] / SCALE,
            countryCode = countryCodes[countryIndex[row].toInt()],
            country = country.name,
            region = regions[regionIndex[row]]?.name,
            population = populations[row],
        )
    }

    private fun rowOfKey(offset: Int): Int {
        var low = 0
        var high = keyStarts.size - 2
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (keyStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    // Bounding box of a spherical cap (Matuschek, "Finding Points Within a Distance of a
    // Latitude/Longitude Using Bounding Coordinates"): every point within radiusKm lies inside it.
    private inline fun forEachRowNear(latitude: Double, longitude: Double, radiusKm: Double, visit: (Int) -> Unit) {
        val angular = radiusKm / EARTH_RADIUS_KM
        val lat = latitude.toRadians()
        val latMin = lat - angular
        val latMax = lat + angular
        val allLongitudes = latMin <= -PI / 2 || latMax >= PI / 2 || sin(angular) >= cos(lat)
        val lonSpan = if (allLongitudes) 180.0 else asin(sin(angular) / cos(lat)).toDegrees()
        val firstLatCell = cellLat(maxOf(latMin.toDegrees(), -90.0))
        val lastLatCell = cellLat(minOf(latMax.toDegrees(), 90.0))
        val firstLonCell = cellLon(longitude - lonSpan)
        val lastLonCell = cellLon(longitude + lonSpan)
        // Cells past the antimeridian wrap around below.
        val lonCells = if (allLongitudes || lastLonCell - firstLonCell + 1 >= LON_CELLS) {
            0 until LON_CELLS
        } else {
            firstLonCell..lastLonCell
        }
        for (latCell in firstLatCell..lastLatCell) {
            for (lonCell in lonCells) {
                val cell = latCell * LON_CELLS + lonCell.mod(LON_CELLS)
                for (i in cellStarts[cell] until cellStarts[cell + 1]) visit(cellRows[i])
            }
        }
    }

    companion object {
        private const val SCALE = 1000.0
        private const val LAT_CELLS = 180
        private const val LON_CELLS = 360
        private const val EARTH_RADIUS_KM = 6371.0088
        private const val HALF_CIRCUMFERENCE_KM = PI * EARTH_RADIUS_KM
        private const val FIRST_RADIUS_KM = 25.0
        private const val RANK_EXACT_NAME = 0
        private const val RANK_EXACT_ALTERNATE = 1
        private const val RANK_NAME_PREFIX = 2
        private const val RANK_ALTERNATE_PREFIX = 3
        private const val RANK_WORD = 4
        const val MAX_QUERY_CHARS = 100
        const val MAX_PARTS = 4
        const val MAX_WORDS = 8

        /** GeoNames alternates include other towns' names ("Victoria" for Hong Kong); see [search]. */
        const val ALTERNATE_WEIGHT = 100.0

        private fun cellLat(degrees: Double) = (floor(degrees).toInt() + 90).coerceIn(0, LAT_CELLS - 1)
        private fun cellLon(degrees: Double) = floor(degrees).toInt() + 180

        /**
         * Builds the table from the generator's four files. Lines starting with '#' are comments.
         * A malformed line fails the whole parse rather than dropping a place.
         */
        fun parse(places: String, regions: String, countries: String, folds: String): PlaceTable {
            val foldMap = HashMap<Char, String>()
            dataLines(folds).forEach { line ->
                val columns = line.split('\t')
                require(columns.size == 2 && columns[0].length == 1) { "bad fold line: $line" }
                foldMap[columns[0][0]] = columns[1]
            }
            val countryNames = HashMap<String, List<String>>()
            dataLines(countries).forEach { line ->
                val columns = line.split('\t')
                require(columns.size == 2 || columns.size == 3) { "bad country line: $line" }
                countryNames[columns[0]] = columns
            }
            val regionNames = HashMap<String, List<String>>()
            dataLines(regions).forEach { line ->
                val columns = line.split('\t')
                require(columns.size == 2 || columns.size == 3) { "bad region line: $line" }
                regionNames[columns[0]] = columns
            }

            val count = dataLines(places).count()
            val display = StringBuilder()
            val nameStarts = IntArray(count + 1)
            val latitudes = IntArray(count)
            val longitudes = IntArray(count)
            val countryIndex = ShortArray(count)
            val regionIndex = IntArray(count)
            val populations = IntArray(count)
            val countryList = ArrayList<Area>()
            val countryCodes = ArrayList<String>()
            val countryPositions = HashMap<String, Int>()
            val regionList = arrayListOf<Area?>(null)
            val regionPositions = HashMap<String, Int>()
            val keys = StringBuilder()
            val keyStarts = IntArray(count + 1)

            dataLines(places).forEachIndexed { row, line ->
                val columns = line.split('\t')
                require(columns.size == 6 || columns.size == 7) { "bad place line: $line" }
                val name = columns[0]
                require(name.isNotEmpty()) { "place without a name: $line" }
                nameStarts[row] = display.length
                display.append(name)
                latitudes[row] = thousandths(columns[1], 90)
                longitudes[row] = thousandths(columns[2], 180)
                val code = columns[3]
                countryIndex[row] = countryPositions.getOrPut(code) {
                    require(countryList.size < Short.MAX_VALUE) { "too many countries" }
                    val country = countryNames[code]
                    val others = country?.getOrNull(2)?.split('|').orEmpty()
                    countryList.add(Area(country?.get(1), areaKey(foldMap, country?.get(1).orEmpty()), codeKeys(foldMap, listOf(code) + others)))
                    countryCodes.add(code)
                    countryList.size - 1
                }.toShort()
                val regionKey = "$code.${columns[4]}"
                val region = regionNames[regionKey]
                regionIndex[row] = if (region == null) 0 else regionPositions.getOrPut(regionKey) {
                    regionList.add(Area(region[1], areaKey(foldMap, region[1]), codeKeys(foldMap, listOfNotNull(columns[4], region.getOrNull(2)))))
                    regionList.size - 1
                }
                populations[row] = requireNotNull(columns[5].toIntOrNull()?.takeIf { it >= 0 }) { "bad population: $line" }
                keyStarts[row] = keys.length
                keys.append(searchKey(foldMap, name))
                if (columns.size == 7) columns[6].split('|').forEach { keys.append('|').append(searchKey(foldMap, it)) }
                keys.append('\n')
            }
            nameStarts[count] = display.length
            keyStarts[count] = keys.length

            // Counting sort of rows into 1-degree cells.
            val cellStarts = IntArray(LAT_CELLS * LON_CELLS + 1)
            val cellOf = IntArray(count) { row ->
                cellLat(latitudes[row] / SCALE) * LON_CELLS + cellLon(longitudes[row] / SCALE).mod(LON_CELLS)
            }
            cellOf.forEach { cellStarts[it + 1]++ }
            for (i in 1 until cellStarts.size) cellStarts[i] += cellStarts[i - 1]
            val fill = cellStarts.copyOf()
            val cellRows = IntArray(count)
            for (row in 0 until count) cellRows[fill[cellOf[row]]++] = row

            return PlaceTable(
                displayNames = display.toString(),
                nameStarts = nameStarts,
                latitudes = latitudes,
                longitudes = longitudes,
                countryIndex = countryIndex,
                regionIndex = regionIndex,
                populations = populations,
                countries = countryList,
                countryCodes = countryCodes,
                regions = regionList,
                keys = keys.toString(),
                keyStarts = keyStarts,
                folds = foldMap,
                cellStarts = cellStarts,
                cellRows = cellRows,
            )
        }

        // fold.tsv lists the changed characters the names use plus the Latin ranges; combining
        // marks are dropped here. Apostrophes and the Catalan middle dot join a word; other
        // punctuation splits it. Keys hold only letters, digits and single spaces, so '|' and
        // line breaks can separate them.
        private fun searchKey(folds: Map<Char, String>, text: String): String {
            val out = StringBuilder(text.length)
            for (c in text) {
                if (c.category == CharCategory.NON_SPACING_MARK) continue
                for (f in folds[c] ?: c.lowercaseChar().toString()) {
                    when {
                        f == '\'' || f == '`' || f == '\u00B7' -> Unit
                        f.isLetterOrDigit() -> out.append(f)
                        out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
                    }
                }
            }
            return out.trimEnd().toString()
        }

        // A run of single letters reads as one word ("B.C." -> "b c" -> "bc"), in queries and in
        // area names alike, so an abbreviation names an area with or without its dots.
        private fun joinInitials(words: List<String>): List<String> {
            val out = ArrayList<String>()
            val run = StringBuilder()
            for (word in words) {
                if (word.length == 1 && word[0].isLetter()) {
                    run.append(word)
                    continue
                }
                if (run.isNotEmpty()) out.add(run.toString()).also { run.clear() }
                out.add(word)
            }
            if (run.isNotEmpty()) out.add(run.toString())
            return out
        }

        private fun areaKey(folds: Map<Char, String>, text: String) =
            joinInitials(searchKey(folds, text).split(' ')).joinToString(" ")

        private fun codeKeys(folds: Map<Char, String>, codes: List<String>) =
            codes.map { areaKey(folds, it) }.filter { it.isNotEmpty() }.toSet()

        private fun dataLines(text: String) = text.lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }

        private fun thousandths(text: String, bound: Int): Int {
            val value = requireNotNull(text.toDoubleOrNull()) { "bad coordinate: $text" }
            require(value >= -bound && value <= bound) { "coordinate out of range: $text" }
            return kotlin.math.round(value * SCALE).toInt()
        }

        internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1.toRadians()
            val p2 = lat2.toRadians()
            val dp = p2 - p1
            val dl = (lon2 - lon1).toRadians()
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
        }

        private fun Double.toRadians() = this * PI / 180.0
        private fun Double.toDegrees() = this * 180.0 / PI
    }
}
