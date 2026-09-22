package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The catalogue is generated data, checked in and reviewed by eye. These are the invariants the app
 * relies on, asserted rather than trusted, because an eye does not reliably catch a missing district
 * in the ninety-third row of a generated file.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceCatalogueTest {

    private val catalogue = PlaceCatalogue(ApplicationProvider.getApplicationContext())

    @Test
    fun `every municipality in the province is present`() = runTest {
        assertEquals(116, catalogue.all().size)
    }

    @Test
    fun `istat codes are unique and six digits`() = runTest {
        val all = catalogue.all()
        assertEquals(all.size, all.map { it.istat }.toSet().size)
        assertTrue(all.all { it.istat.length == 6 && it.istat.all(Char::isDigit) })
    }

    /** A wrong district shows the wrong valley's bulletin; a missing one shows none at all. */
    @Test
    fun `every place names a district that exists`() = runTest {
        assertTrue(catalogue.all().all { it.district in 1..7 })
    }

    /** All seven are in use; one standing empty would mean a whole valley had been misfiled. */
    @Test
    fun `no district is left empty`() = runTest {
        assertEquals((1..7).toSet(), catalogue.all().map { it.district }.toSet())
    }

    @Test
    fun `every place has a name in all three languages`() = runTest {
        assertTrue(catalogue.all().all { it.nameDe.isNotBlank() && it.nameIt.isNotBlank() && it.nameEn.isNotBlank() })
    }

    /** The inhabited province runs from the Unterland at about 200 m to villages above 1500 m. */
    @Test
    fun `altitudes are inside the province`() = runTest {
        assertTrue(catalogue.all().all { it.altitudeM in 150..2200 })
    }

    /** Beyond the cap a station is not speaking about the place, and the generator drops it. */
    @Test
    fun `no place claims a station further away than the cap`() = runTest {
        assertTrue(catalogue.all().mapNotNull { it.station }.all { it.distanceKm <= 20.0 })
    }

    @Test
    fun `the default place is Dorf Tirol and it is complete`() = runTest {
        val p = catalogue.byIstat("021101")
        assertNotNull(p)
        assertEquals("Dorf Tirol", p!!.nameDe)
        assertEquals("Tirolo", p.nameIt)
        assertEquals(2, p.district)
        assertEquals("23200MS", p.station?.code)
    }

    @Test
    fun `an unknown code resolves to nothing rather than to something wrong`() = runTest {
        assertNull(catalogue.byIstat("999999"))
    }

    @Test
    fun `the name follows the reader's language`() = runTest {
        val p = catalogue.byIstat("021101")!!
        assertEquals("Dorf Tirol", p.name(Locale.GERMAN))
        assertEquals("Tirolo", p.name(Locale.ITALIAN))
        assertEquals("Tirol", p.name(Locale.ENGLISH))
    }

    @Test
    fun `search matches every language`() = runTest {
        assertTrue(catalogue.search("tirolo", Locale.ITALIAN).any { it.istat == "021101" })
        assertTrue(catalogue.search("dorf", Locale.GERMAN).any { it.istat == "021101" })
        assertTrue(catalogue.search("merano", Locale.ITALIAN).any { it.nameDe == "Meran" })
    }

    /** "molten" must find Mölten without the reader reaching for the umlaut, and so must "moelten". */
    @Test
    fun `search ignores case and diacritics`() = runTest {
        assertTrue(catalogue.search("moelten", Locale.GERMAN).any { it.nameDe == "Mölten" })
        assertTrue(catalogue.search("molten", Locale.GERMAN).any { it.nameDe == "Mölten" })
        assertTrue(catalogue.search("MERAN", Locale.GERMAN).any { it.nameDe == "Meran" })
    }

    @Test
    fun `a query matching nothing returns nothing`() = runTest {
        assertTrue(catalogue.search("zzzzz", Locale.GERMAN).isEmpty())
    }

    /**
     * The folded names are built once and kept, so a second search must give exactly what the first
     * did — including one that runs after the by-code index has been built, since both hang off the
     * same lazily read asset.
     */
    @Test
    fun `searching twice gives the same answer, and so does searching after a lookup`() = runTest {
        val first = catalogue.search("mer", Locale.GERMAN).map { it.istat }
        assertNotNull(catalogue.byIstat("021101"))
        assertEquals(first, catalogue.search("mer", Locale.GERMAN).map { it.istat })
        assertTrue(first.isNotEmpty())
    }

    /** Alphabetical the way the reader's language is alphabetical, not the way ASCII is. */
    @Test
    fun `an empty query returns everything, in the reader's own order`() = runTest {
        val results = catalogue.search("", Locale.GERMAN)
        assertEquals(116, results.size)
        val collator = java.text.Collator.getInstance(Locale.GERMAN).apply { strength = java.text.Collator.PRIMARY }
        assertEquals(results.map { it.nameDe }.sortedWith(collator), results.map { it.nameDe })
    }

    /** The list is ordered by the name the reader actually sees, which differs by language. */
    @Test
    fun `the order follows the language the names are shown in`() = runTest {
        val italian = catalogue.search("", Locale.ITALIAN).map { it.nameIt }
        val collator = java.text.Collator.getInstance(Locale.ITALIAN).apply { strength = java.text.Collator.PRIMARY }
        assertEquals(italian.sortedWith(collator), italian)
    }

    /**
     * An amateur station never replaces the provincial one; it stands beside it, and
     * [Place.readingStation] is the one everything else asks. The provincial record is what the app
     * falls back to when the amateur station goes quiet — which it does routinely, since Weather
     * Underground answers "nothing in the last 60 minutes" for a live station too.
     */
    @Test
    fun `a place with an amateur station reads it and keeps the provincial one`() {
        val json = """
            [{"istat":"021101","nameDe":"Dorf Tirol","nameIt":"Tirolo","nameEn":"Tirol",
              "lat":46.688958,"lon":11.156624,"altitudeM":594,"district":2,
              "station":{"code":"23200MS","name":"Meran","lat":46.688,"lon":11.1366,
                         "altitudeM":330,"distanceKm":1.53},
              "pws":{"network":"wu","code":"ITIROL16","name":"Tirolo - Tirol",
                     "lat":46.693246,"lon":11.155237,"altitudeM":634,"distanceKm":0.49}}]
        """.trimIndent()
        val place = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(it.apexweather.domain.Place.serializer()),
                json,
            )
            .single()
        assertEquals("ITIROL16", place.pws!!.code)
        assertEquals("wu", place.pws!!.network)
        assertEquals("23200MS", place.station!!.code)
        // Defaulted, so a catalogue written before this reads as the province's own — which it is.
        assertEquals("siag", place.station!!.network)
        assertEquals("ITIROL16", place.readingStation!!.code)
    }

    @Test
    fun `a place without an amateur station reads the provincial one`() {
        val json = """
            [{"istat":"021115","nameDe":"Sterzing","nameIt":"Vipiteno","nameEn":"Vipiteno",
              "lat":46.8967,"lon":11.4333,"altitudeM":948,"district":5,
              "station":{"code":"X","name":"X","lat":46.9,"lon":11.4,"altitudeM":900,"distanceKm":2.0}}]
        """.trimIndent()
        val place = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(it.apexweather.domain.Place.serializer()),
                json,
            )
            .single()
        assertNull(place.pws)
        assertEquals("X", place.readingStation!!.code)
    }

    /** A place with neither has nothing to measure, and that is not a failure. */
    @Test
    fun `a place with no station at all reads nothing`() {
        val json = """
            [{"istat":"021115","nameDe":"Sterzing","nameIt":"Vipiteno","nameEn":"Vipiteno",
              "lat":46.8967,"lon":11.4333,"altitudeM":948,"district":5}]
        """.trimIndent()
        val place = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(it.apexweather.domain.Place.serializer()),
                json,
            )
            .single()
        assertNull(place.readingStation)
    }
}
