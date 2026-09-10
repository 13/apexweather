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
}
