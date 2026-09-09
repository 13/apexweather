package it.apexweather.ui.bulletin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.BulletinDay
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BulletinScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun day(offset: Long, description: String, iconUrl: String? = null, rainTo: Double? = 2.0) =
        BulletinDay(LocalDate.now().plusDays(offset), "b", description, iconUrl, 12.0, 25.0, 0.0, rainTo, 0)

    private fun bulletin(days: List<BulletinDay>) =
        Bulletin("de", Instant.now(), "Sonnig", "Viel Sonne.\nMorgen Regen.", emptyList(), days)

    @Test
    fun rendersTextAndDays() {
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, bulletin(listOf(day(0, "Heiter"))), null)) } }
        rule.onNodeWithTag("bulletin_text").assertIsDisplayed()
        rule.onNodeWithTag("bulletin_day_0").assertIsDisplayed()
    }

    /**
     * The district cards used to size themselves independently, so a day whose description wrapped
     * onto a second or third line stood taller than its neighbours and the row looked ragged. These
     * four days differ in description length, in whether they carry an icon, and in whether they
     * have a rain range — every dimension that used to move the height.
     */
    @Test
    fun districtDayCardsAllShareTheTallestHeight() {
        val days = listOf(
            day(0, "Heiter"),
            day(1, "Wolkig, Gewitter mit mäßigen Schauern am Nachmittag"),
            day(2, "Stark bewölkt", iconUrl = null, rainTo = null),
            day(3, "Bedeckt, mäßiger Regen"),
        )
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, bulletin(days), null)) } }

        val heights = days.indices.map { rule.onNodeWithTag("bulletin_day_$it").getUnclippedBoundsInRoot().height }
        heights.forEach { assertEquals(heights.first(), it) }
    }

    @Test
    fun emptyState() {
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, null, null)) } }
        rule.onNodeWithTag("bulletin_empty").assertIsDisplayed()
    }
}
