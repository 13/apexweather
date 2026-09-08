package it.apexweather.ui.bulletin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.BulletinDay
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BulletinScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rendersTextAndDays() {
        val b = Bulletin("de", Instant.now(), "Sonnig", "Viel Sonne.\nMorgen Regen.", emptyList(),
            listOf(BulletinDay(LocalDate.now(), "b", "Heiter", null, 12.0, 25.0, 0.0, 2.0, 0)))
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, b, null)) } }
        rule.onNodeWithTag("bulletin_text").assertIsDisplayed()
        rule.onNodeWithTag("bulletin_day_0").assertIsDisplayed()
    }

    @Test
    fun emptyState() {
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, null, null)) } }
        rule.onNodeWithTag("bulletin_empty").assertIsDisplayed()
    }
}
