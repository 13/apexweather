package it.apexweather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Nothing the reader sees may name one of the 116 municipalities.
 *
 * The place is a setting. A hardcoded name is therefore not a cosmetic slip but a lie told to the
 * other 115, and it keeps coming back: three survived the first pass and were only caught by
 * switching place on the phone; a fourth sat in the widget picker's preview layout, which the app
 * never renders; and two more sat in `about` and `widget_description`, one of which the launcher
 * shows beside the widget and neither of which any screen test opens.
 *
 * A phone cannot catch the ones outside the app, so this reads the resources instead. It looks at
 * every string in every language and every layout, in all three languages' spellings of every
 * municipality, which is the only form of this check that would have caught all six.
 */
class NoHardcodedPlaceNamesTest {

    /**
     * Strings that name a municipality and are right to.
     *
     * The seven weather districts are named after the valleys and towns they cover — the reader
     * chooses among them nowhere, they are simply what the bulletin's own areas are called — so
     * `district_1` really does have to say "Bozen" and `district_4` "Sarntal".
     *
     * And `map_attribution` credits the province, whose name is "Autonome Provinz Bozen – Südtirol"
     * and "Provincia autonoma di Bolzano – Alto Adige". That is the map's author, not the reader's
     * village.
     */
    private val allowed = setOf(
        "district_1", "district_2", "district_3", "district_4", "district_5", "district_6", "district_7",
        "map_attribution",
    )

    /**
     * Names too ordinary to search for. The Italian for Auer is "Ora", which is also the Italian for
     * "hour" and for "now", and appears in half the app.
     */
    private val tooCommon = setOf("Ora")

    private val res = File("src/main/res")

    private fun municipalityNames(): Set<String> {
        val text = File("src/main/assets/places.json").readText()
        val places = Json.parseToJsonElement(text) as JsonArray
        return places.flatMap { place ->
            listOf("nameDe", "nameIt", "nameEn").mapNotNull { place.jsonObject[it]?.jsonPrimitive?.content }
        }.toSet() - tooCommon
    }

    /** Whole words only: "Tirol" must not be found inside "Tirolo", nor "Gais" inside "Gaiser". */
    private fun mentions(text: String, name: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

    @Test
    fun `no string resource names a municipality`() {
        val names = municipalityNames()
        val offenders = res.walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText())
                    .filter { it.groupValues[1] !in allowed }
                    .flatMap { match ->
                        names.filter { mentions(match.groupValues[2], it) }
                            .map { "${file.parentFile.name}/${match.groupValues[1]}: $it" }
                    }
            }
            .sorted().toList()
        assertEquals("the place is a setting; these name one village to all 116", emptyList<String>(), offenders)
    }

    /**
     * And no layout either. This is the one the app itself cannot show you: `widget_preview.xml` is
     * inflated by the launcher, never by us, so it renders in no test and on no screen.
     */
    @Test
    fun `no layout names a municipality`() {
        val names = municipalityNames()
        val offenders = res.resolve("layout").walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { file -> names.filter { mentions(file.readText(), it) }.map { "${file.name}: $it" } }
            .sorted().toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
