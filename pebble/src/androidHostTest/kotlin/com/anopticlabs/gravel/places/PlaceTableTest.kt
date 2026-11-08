package com.anopticlabs.gravel.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaceTableTest {

    private val folds = """
        # a comment
        Ü	u
        ü	u
        é	e
        ´	'
    """.trimIndent()
    private val countries = "AT\tAustria\nCH\tSwitzerland\nFJ\tFiji\nUS\tUnited States\n"
    private val regions = "AT.09\tVienna\nUS.OR\tOregon\nUS.ME\tMaine\nUS.CA\tCalifornia\nUS.MS\tMississippi\nNZ.E7\tAuckland\n"

    private fun table(places: String) = PlaceTable.parse(places.trimIndent(), regions, countries, folds)

    private val sample = table(
        """
            # Towns: a header line
            Vienna	48.208	16.372	AT	09	1691	Wien|Vienne
            Zürich	47.367	8.55	CH	ZH	415
            Portland	45.523	-122.676	US	OR	652
            Portland	43.661	-70.255	US	ME	68
            South Portland	43.641	-70.241	US	ME	26
            Newport	44.637	-124.053	US	OR	10
            Suva	-18.142	178.441	FJ	01	77
            Lambasa	-16.417	179.383	FJ	03	4
        """,
    )

    @Test
    fun parsesRowsAndNamesRegionsAndCountries() {
        assertEquals(8, sample.size)
        val vienna = sample.search("vienna", 1, typing = true).single()
        assertEquals(Town("Vienna", 48.208, 16.372, "AT", "Austria", "Vienna", 1691), vienna)
        assertNull(sample.search("zurich", 1, typing = true).single().region, "a region code missing from regions.tsv reads as no region")
    }

    @Test
    fun nearestFindsTheClosestTown() {
        assertEquals("Newport", sample.nearest(44.6, -124.0)?.name)
        assertEquals("Maine", sample.nearest(43.65, -70.26)?.region)
    }

    @Test
    fun nearestLooksAcrossTheAntimeridian() {
        // Lambasa is 76 km away across the line; the decoy, on this side, is 150 km away.
        val table = table(
            """
                Lambasa	-16.417	179.383	FJ	03	4
                Decoy	-17.4	-179.0	FJ	03	1
            """,
        )
        assertEquals("Lambasa", table.nearest(-16.4, -179.9)?.name)
    }

    @Test
    fun aTieGoesToTheLargerPlace() {
        val table = table(
            """
                Kentron	40.177	44.513	AM	11	133
                Yerevan	40.177	44.513	AM	11	1145
            """,
        )
        assertEquals("Yerevan", table.nearest(40.2, 44.5)?.name)
    }

    @Test
    fun distanceIsGreatCircle() {
        assertEquals(343.6, PlaceTable.distanceKm(48.8566, 2.3522, 51.5074, -0.1278), 0.5)
    }

    @Test
    fun nearestWidensUntilItFindsSomething() {
        val lonely = table("Only	-45.0	170.0	US	OR	1")
        assertEquals("Only", lonely.nearest(60.0, -10.0)?.name)
        assertEquals("Only", lonely.nearest(90.0, 0.0)?.name)
        assertEquals("Only", lonely.nearest(-90.0, 180.0)?.name)
    }

    @Test
    fun nearestOnAnEmptyTableIsNull() {
        assertNull(table("# nothing").nearest(0.0, 0.0))
    }

    @Test
    fun nearestRejectsPositionsOffTheMap() {
        assertFailsWith<IllegalArgumentException> { sample.nearest(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { sample.nearest(0.0, 180.5) }
        assertFailsWith<IllegalArgumentException> { sample.nearest(Double.NaN, 0.0) }
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertEquals("Zürich", sample.search("ZURICH", 5, typing = true).single().name)
        assertEquals("Zürich", sample.search("Zür", 5, typing = true).single().name)
        assertEquals("Zürich", sample.search("  zürich  ", 5, typing = true).single().name)
        assertEquals("zurich", sample.searchKey("Zu\u0308rich"), "a decomposed accent is dropped")
    }

    @Test
    fun searchFindsAlternateNames() {
        assertEquals("Vienna", sample.search("wien", 5, typing = true).single().name)
        assertEquals("Vienna", sample.search("vienne", 5, typing = true).single().name)
    }

    @Test
    fun searchIgnoresPunctuation() {
        val table = table(
            """
                Aix-en-Provence	43.528	5.445	FR	93	147
                St. Louis	38.627	-90.198	US	MO	302
                Rosemont–La Petite-Patrie	45.544	-73.597	CA	10	139
                L'Isle-Adam	49.111	2.223	FR	11	12
            """,
        )
        assertEquals("Aix-en-Provence", table.search("aix en provence", 5, typing = true).single().name)
        assertEquals("St. Louis", table.search("st louis", 5, typing = true).single().name)
        assertEquals("St. Louis", table.search("St.Louis", 5, typing = true).single().name)
        assertEquals("Rosemont–La Petite-Patrie", table.search("petite patrie", 5, typing = true).single().name)
        assertEquals("L'Isle-Adam", table.search("lisle adam", 5, typing = true).single().name)
    }

    @Test
    fun apostrophesBackticksAndMiddleDotsJoinAWord() {
        val table = table(
            """
                L'Aquila	42.351	13.398	IT	65	70
                Giv`at Shmuel	32.078	34.848	IL	02	27
                Paral·lel	41.375	2.163	ES	56	1
            """,
        )
        assertEquals("L'Aquila", table.search("L´Aquila", 5, typing = true).single().name)
        assertEquals("Giv`at Shmuel", table.search("givat shmuel", 5, typing = true).single().name)
        assertEquals("Paral·lel", table.search("parallel", 5, typing = true).single().name)
    }

    @Test
    fun aPlaceTheListLacksFindsNothing() {
        val table = table(
            """
                Kansas City	39.1	-94.579	US	MO	508
                New York City	40.714	-74.006	US	NY	8804
                Lagos	6.454	3.395	NG	25	15388
            """,
        )
        assertEquals(emptyList(), table.search("Fakeville, Kansas", 5, typing = true))
        assertEquals(emptyList(), table.search("New Fakeville", 5, typing = true))
        assertEquals(emptyList(), table.search("L Fakeville", 5, typing = true))
        assertEquals(listOf("Kansas City"), table.search("kansas", 5, typing = true).map { it.name }, "still offered while typing")
    }

    @Test
    fun aOneWordTownDoesNotTakeOverALongerName() {
        val table = table(
            """
                San	13.3	-4.9	ML	10	54
                San Francisco	37.775	-122.419	US	CA	874
                Pearl	32.274	-90.132	US	MS	27
                Portland	45.523	-122.676	US	OR	652
                Mission Bay	-36.851	174.833	NZ	E7	7
            """,
        )
        assertEquals("San Francisco", table.search("san fr", 5, typing = true).first().name, "while typing")
        assertEquals("San", table.search("san", 5, typing = true).first().name)
        assertEquals(emptyList(), table.search("Pearl District, Portland, OR", 5, typing = false))
        assertEquals("Mission Bay", table.search("Mission Bay, San Francisco, CA", 5, typing = false).first().name, "the first part is the town")
        assertEquals(emptyList(), table.search("San Fakeville", 5, typing = false), "a watchapp's lookup does not guess")
        assertEquals("San", table.search("San Fakeville", 5, typing = true).first().name, "the search screen offers it")
    }

    @Test
    fun regionCodesPeopleTypeAndPostcodesPlaceATown() {
        val table = PlaceTable.parse(
            """
                London	51.509	-0.126	GB	ENG	8961
                London	42.984	-81.233	CA	08	346
                Vancouver	49.25	-123.119	CA	02	600
                Vancouver	45.639	-122.661	US	WA	190
                Halifax	44.649	-63.576	CA	07	318
                Halifax	53.716	-1.857	GB	ENG	88
                Downtown	51.045	-114.065	CA	01	10
                San Francisco	37.775	-122.419	US	CA	874
            """.trimIndent(),
            "GB.ENG\tEngland\nCA.08\tOntario\tON\nCA.02\tBritish Columbia\tBC\nCA.07\tNova Scotia\tNS\n" +
                "CA.01\tAlberta\tAB\nUS.WA\tWashington\nUS.CA\tCalifornia\n",
            "GB\tUnited Kingdom\tGBR|UK\nCA\tCanada\tCAN\nUS\tUnited States\tUSA\n",
            folds,
        )
        assertEquals("GB", table.search("London UK", 5, typing = false).first().countryCode)
        assertEquals("CA", table.search("London ON", 5, typing = false).first().countryCode)
        assertEquals("CA", table.search("Vancouver BC", 5, typing = false).first().countryCode)
        assertEquals("US", table.search("Vancouver WA USA", 5, typing = false).first().countryCode)
        assertEquals("CA", table.search("Halifax Nova Scotia", 5, typing = false).first().countryCode, "a two-word region")
        assertEquals("San Francisco", table.search("San Francisco CA 94110", 5, typing = false).first().name)
        assertEquals("CA", table.search("Vancouver 98660", 5, typing = false).first().countryCode, "a postcode places without ranking")
        assertEquals("Downtown", table.search("Downtown, San Francisco, CA", 5, typing = false).first().name, "the first part is the town")
        assertEquals("CA", table.search("Vancouver b", 5, typing = true).map { it.countryCode }.sorted().first(), "both shown while typing")
        assertEquals(2, table.search("Vancouver b", 5, typing = true).count { it.name == "Vancouver" })
        assertEquals(emptyList(), table.search("London Fakeshire", 5, typing = false))
        assertEquals(emptyList(), table.search("Vancouver BC Fakeshire", 5, typing = false), "every dropped word counts")
    }

    @Test
    fun aWatchappLookupReturnsExactMatchesOnly() {
        val table = PlaceTable.parse(
            "New Orleans\t29.955\t-90.075\tUS\tLA\t384\nOrleans\t45.467\t-75.517\tCA\t08\t100\n",
            "US.LA\tLouisiana\nCA.08\tOntario\tON\n",
            "US\tUnited States\tUSA\nCA\tCanada\tCAN\n",
            folds,
        )
        assertEquals(listOf("Orleans"), table.search("Orleans, LA", 5, typing = false).map { it.name })
        assertEquals("Orleans", table.search("Orleans, LA", 5, typing = true).first().name, "exact matches first")
    }

    @Test
    fun aCountryOutranksARegionWhoseNameStartsLikeIt() {
        // The Indiana Salem is the bigger one here, so population alone would pick it.
        val table = PlaceTable.parse(
            "Salem\t11.65\t78.16\tIN\t25\t917\nSalem\t38.606\t-86.101\tUS\tIN\t1000\n",
            "IN.25\tTamil Nadu\nUS.IN\tIndiana\n",
            "IN\tIndia\tIND\nUS\tUnited States\tUSA\n",
            folds,
        )
        assertEquals("IN", table.search("Salem, India", 5, typing = false).first().countryCode)
        assertEquals("IN", table.search("Salem India", 5, typing = false).first().countryCode)
        assertEquals("US", table.search("Salem, Indiana", 5, typing = false).first().countryCode)
        assertEquals("US", table.search("Salem, Indi", 5, typing = false).first().countryCode, "a region's start outranks a country's")
    }

    @Test
    fun aWordNamingTheCountryDoesNotFavorARegionOfTheSameName() {
        val table = PlaceTable.parse(
            "Santa Catarina\t19.317\t-99.483\tMX\t15\t5\nSanta Catarina\t25.673\t-100.458\tMX\t19\t304\n",
            "MX.15\tMéxico\nMX.19\tNuevo Leon\n",
            "MX\tMexico\tMEX\n",
            folds,
        )
        assertEquals("Nuevo Leon", table.search("Santa Catarina, Mexico", 5, typing = false).first().region)
        assertEquals("Nuevo Leon", table.search("Santa Catarina Mexico", 5, typing = false).first().region)
    }

    @Test
    fun aRegionCodeOutranksTheSameCountryCode() {
        val table = PlaceTable.parse(
            "Avalon\t47.3\t-53.0\tCA\t05\t1\nAvalon\t52.4\t4.9\tNL\t07\t100\n",
            "CA.05\tNewfoundland and Labrador\tNL\nNL.07\tNorth Holland\n",
            "CA\tCanada\tCAN\nNL\tNetherlands\tNLD\n",
            folds,
        )
        assertEquals("CA", table.search("Avalon, NL", 5, typing = false).first().countryCode)
    }

    @Test
    fun theWordsThatNameAnAreaAddUp() {
        // Kent: the English county's name (4); USA plus the start of Kentucky: 3 + 2.
        val table = PlaceTable.parse(
            "Ashland\t51.2\t0.9\tGB\tKNT\t30\nAshland\t38.478\t-82.638\tUS\tKY\t21\n",
            "GB.KNT\tKent\nUS.KY\tKentucky\n",
            "GB\tUnited Kingdom\tGBR|UK\nUS\tUnited States\tUSA\n",
            folds,
        )
        assertEquals("US", table.search("Ashland, Kent, USA", 5, typing = false).first().countryCode)
    }

    @Test
    fun laterPartsRankTheTownsTheLeadingWordsPlace() {
        val table = PlaceTable.parse(
            "Springfield\t39.802\t-89.644\tUS\tIL\t114\nSpringfield\t37.215\t-93.298\tUS\tMO\t169\n",
            "US.IL\tIllinois\nUS.MO\tMissouri\n",
            "US\tUnited States\tUSA\n",
            folds,
        )
        assertEquals("Illinois", table.search("Springfield US, Illinois", 5, typing = false).first().region)
    }

    @Test
    fun theSearchScreenListsOtherMatchesAfterThePlacedTown() {
        val table = PlaceTable.parse(
            "Rochester\t43.155\t-77.616\tUS\tNY\t211\nRochester\t44.022\t-92.47\tUS\tMN\t121\n" +
                "Rochester\t42.681\t-83.134\tUS\tMI\t13\nRio de Janeiro\t-22.906\t-43.173\tBR\t21\t6748\tRio\n" +
                "Rio Branco\t-9.975\t-67.81\tBR\t01\t336\n",
            "US.NY\tNew York\nUS.MN\tMinnesota\nUS.MI\tMichigan\nBR.21\tRio de Janeiro\nBR.01\tAcre\n",
            "US\tUnited States\tUSA\nBR\tBrazil\tBRA\n",
            folds,
        )
        assertEquals(listOf("Michigan"), table.search("Rochester Mi", 5, typing = false).map { it.region })
        assertEquals(listOf("Michigan", "New York", "Minnesota"), table.search("Rochester Mi", 5, typing = true).map { it.region })
        assertEquals(listOf("Rio de Janeiro"), table.search("rio br", 5, typing = false).map { it.name })
        assertEquals(listOf("Rio de Janeiro", "Rio Branco"), table.search("rio br", 5, typing = true).map { it.name })
    }

    @Test
    fun initialsInAnAreaNameMatchTypedInitials() {
        val table = PlaceTable.parse(
            "Kennedy\t4.63\t-74.15\tCO\t34\t980\nCharlotte Amalie\t18.342\t-64.931\tVI\t03\t20\n",
            "CO.34\tBogota D.C.\n",
            "CO\tColombia\tCOL\nVI\tU.S. Virgin Islands\tVIR\n",
            folds,
        )
        assertEquals(listOf("Kennedy"), table.search("Kennedy Bogota D.C.", 5, typing = false).map { it.name })
        assertEquals(listOf("Kennedy"), table.search("Kennedy Bogota DC", 5, typing = false).map { it.name })
        assertEquals(listOf("Charlotte Amalie"), table.search("Charlotte Amalie U.S. Virgin Islands", 5, typing = false).map { it.name })
    }

    @Test
    fun theSearchScreenFallbackReadsInitialsAsOneWord() {
        val table = PlaceTable.parse(
            "Victoria\t48.428\t-123.365\tCA\t02\t289\nVictoria\t22.285\t114.158\tHK\tNA\t1000\n",
            "CA.02\tBritish Columbia\tBC\n",
            "CA\tCanada\tCAN\nHK\tHong Kong\tHKG\n",
            folds,
        )
        assertEquals("British Columbia", table.search("Victoria B.C. Fake", 5, typing = true).first().region)
    }

    @Test
    fun dottedAbbreviationsAndThreeWordRegionsNameAnArea() {
        val table = PlaceTable.parse(
            "Vancouver\t49.25\t-123.119\tCA\t02\t600\nVancouver\t45.639\t-122.661\tUS\tWA\t190\n" +
                "Charlottetown\t46.235\t-63.126\tCA\t09\t36\n",
            "CA.02\tBritish Columbia\tBC\nUS.WA\tWashington\nCA.09\tPrince Edward Island\tPE\n",
            "CA\tCanada\tCAN\nUS\tUnited States\tUSA\n",
            folds,
        )
        assertEquals(listOf("CA"), table.search("Vancouver BC", 5, typing = false).map { it.countryCode }, "only the place it names")
        assertEquals("CA", table.search("Vancouver B.C.", 5, typing = false).first().countryCode)
        assertEquals("US", table.search("Vancouver, U.S.A.", 5, typing = false).first().countryCode)
        assertEquals("Charlottetown", table.search("Charlottetown Prince Edward Island", 5, typing = false).single().name)
    }

    @Test
    fun aRegionNameOfFourWordsPlacesATown() {
        val table = PlaceTable.parse(
            "Porto Alegre\t-30.033\t-51.23\tBR\t23\t1372\nPorto\t41.15\t-8.611\tPT\t17\t249\n" +
                "Santa Maria\t-29.684\t-53.807\tBR\t23\t272\nSanta Maria\t-5.838\t-35.692\tBR\t22\t5\n",
            "BR.22\tRio Grande do Norte\nBR.23\tRio Grande do Sul\nPT.17\tPorto\n",
            "BR\tBrazil\nPT\tPortugal\n",
            folds,
        )
        assertEquals("Porto Alegre", table.search("Porto Alegre Rio Grande do Sul", 5, typing = false).single().name)
        // The bigger Santa Maria's region shares the first three words.
        assertEquals("Rio Grande do Norte", table.search("Santa Maria Rio Grande do Norte", 5, typing = true).first().region)
    }

    @Test
    fun theSearchScreenFallsBackToTheLongestLeadingName() {
        val table = table(
            """
                San	13.3	-4.9	ML	10	54
                San Jose	37.339	-121.895	US	CA	1013
            """,
        )
        assertEquals("San Jose", table.search("San Jose Fake", 5, typing = true).first().name)
        assertEquals(emptyList(), table.search("San Jose Fake", 5, typing = false))
    }

    @Test
    fun numbersDoNotRankResults() {
        val table = PlaceTable.parse(
            "Saint-Maurice\t43.79\t5.85\tFR\t93\t1\nSaint-Maurice\t48.822\t2.427\tFR\t11\t14\n",
            "FR.93\tProvence-Alpes-Cote d'Azur\nFR.11\tIle-de-France\n",
            "FR\tFrance\tFRA\n",
            folds,
        )
        assertEquals("Ile-de-France", table.search("Saint-Maurice 93", 5, typing = false).first().region)
    }

    @Test
    fun aRegionNameNeedsThreeLettersToPlace() {
        val table = PlaceTable.parse(
            "San\t13.3\t-4.9\tML\t10\t54\nSan Sebastian\t43.312\t-1.975\tES\t59\t186\n",
            "ML.10\tSegou\nES.59\tBasque Country\n",
            "ML\tMali\tMLI\nES\tSpain\tESP\n",
            folds,
        )
        assertEquals("San Sebastian", table.search("san se", 5, typing = true).first().name)
        assertEquals("San", table.search("san seg", 5, typing = true).first().name)
    }

    @Test
    fun aCityAndRegionCodeWithoutACommaFindsTheCity() {
        val table = table(
            """
                San José Casas Caídas	17.1	-96.7	MX	20	2
                San Jose	37.339	-121.895	US	CA	1013
            """,
        )
        assertEquals("San Jose", table.search("San Jose CA", 5, typing = true).first().name)
    }

    @Test
    fun aCountryQualifierOutranksPopulation() {
        val table = table(
            """
                Paris	48.853	2.349	FR	11	2138
                Paris	33.661	-95.556	US	TX	25
            """,
        )
        assertEquals("US", table.search("Paris, US", 5, typing = true).first().countryCode)
        assertEquals("US", table.search("Paris, United States", 5, typing = true).first().countryCode)
        assertEquals("FR", table.search("Paris", 5, typing = true).first().countryCode)
    }

    @Test
    fun aLaterPartIsNeverTheTown() {
        assertEquals(emptyList(), sample.search("Fakeville, Portland, Maine", 5, typing = false))
        assertEquals(emptyList(), sample.search("Fakeville, Portland, Maine", 5, typing = true))
        assertEquals(emptyList(), sample.search("Fakeville, Portland", 5, typing = false))
    }

    @Test
    fun onlyTheFirstPartsAndWordsAreRead() {
        assertEquals("Maine", sample.search("Portland, a, b, ME", 5, typing = false).first().region)
        assertEquals("Oregon", sample.search("Portland, a, b, c, ME", 5, typing = false).first().region, "a fifth part is not read")
        assertEquals("Maine", sample.search("Portland me me me me me me me", 5, typing = true).first().region)
        assertEquals("Maine", sample.search("Portland me me me me me me me or", 5, typing = false).first().region, "a ninth word is not read")
        assertEquals("Maine", sample.search("Portland, ME", 5, typing = true).first().region)
        assertEquals("Oregon", sample.search("Portland" + " ".repeat(PlaceTable.MAX_QUERY_CHARS) + ", ME", 5, typing = true).first().region)
    }

    @Test
    fun partsAfterTheNameRankTheirRegionOrCountryFirst() {
        assertEquals("Maine", sample.search("Portland, ME", 5, typing = true).first().region)
        assertEquals("Maine", sample.search("portland, maine", 5, typing = true).first().region)
        assertEquals("Maine", sample.search("Portland ME", 5, typing = true).first().region)
        assertEquals("Oregon", sample.search("Portland, United States", 5, typing = true).first().region)
        assertEquals(emptyList(), sample.search("mission district, portland, oregon, 97201", 5, typing = false), "a later part is never the town")
    }

    @Test
    fun aTownsOwnNameOutranksAnotherTownsAlternate() {
        val table = table(
            """
                Bordeaux	44.84	-0.581	FR	75	260	Porto|Burdigala
                Porto	41.149	-8.611	PT	17	249
                Köln	50.933	6.95	DE	07	1025	Cologne
                Cologne	45.584	9.942	IT	09	7
            """,
        )
        assertEquals("Porto", table.search("porto", 5, typing = true).first().name)
        assertEquals("Köln", table.search("cologne", 5, typing = true).first().name, "a hundred times bigger")
    }

    @Test
    fun aSmallerExactNameOutranksABiggerWordMatch() {
        val table = table(
            """
                New York City	40.714	-74.006	US	NY	8804
                York	53.958	-1.082	GB	ENG	153
                Yorkton	51.217	-102.467	CA	11	16
            """,
        )
        assertEquals(listOf("York", "Yorkton", "New York City"), table.search("york", 5, typing = true).map { it.name })
    }

    @Test
    fun searchRanksExactThenPrefixThenWordByPopulation() {
        val towns = sample.search("portland", 5, typing = true)
        assertEquals(listOf("Oregon", "Maine", "Maine"), towns.map { it.region })
        assertEquals("South Portland", towns.last().name)
    }

    @Test
    fun searchMatchesOnlyAtTheStartOfAWord() {
        assertEquals(listOf("South Portland"), sample.search("south", 5, typing = true).map { it.name })
        assertEquals(emptyList(), sample.search("ortland", 5, typing = true))
        assertEquals(emptyList(), sample.search("ien", 5, typing = true))
    }

    @Test
    fun separatorsInTheQueryDoNotJoinTwoNames() {
        assertEquals(listOf("Vienna"), sample.search("wien|vien", 5, typing = true).map { it.name })
        assertEquals(emptyList(), sample.search("|\n|", 5, typing = true))
    }

    @Test
    fun searchHonorsTheLimitAndEmptyQueries() {
        assertEquals(2, sample.search("portland", 2, typing = true).size)
        assertEquals(emptyList(), sample.search("   ", 5, typing = true))
        assertEquals(emptyList(), sample.search("portland", 0, typing = true))
    }

    @Test
    fun malformedLinesFailTheParse() {
        val bad = listOf(
            "Vienna	48.208	16.372	AT	09",
            "Vienna	north	16.372	AT	09	1",
            "Vienna	91	16.372	AT	09	1",
            "Vienna	48.2	-180.1	AT	09	1",
            "	48.2	16.3	AT	09	1",
            "Vienna	48.2	16.3	AT	09	-1",
        )
        bad.forEach { line -> assertFailsWith<IllegalArgumentException>(line) { PlaceTable.parse(line, regions, countries, folds) } }
        assertFailsWith<IllegalArgumentException> { PlaceTable.parse("", regions, countries, "üu\n") }
    }
}
