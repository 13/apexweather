package it.apexweather.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aRowShowsItsLabelAndItsSupportingLine() {
        rule.setContent {
            ApexTheme {
                SettingRow(Icons.Rounded.Place, label = "Ort", supporting = "wo die Messung herkommt") {}
            }
        }
        rule.onNodeWithText("Ort").assertIsDisplayed()
        rule.onNodeWithText("wo die Messung herkommt").assertIsDisplayed()
    }

    /** A row without one must not leave a gap where a second line would have been. */
    @Test
    fun aRowWithNoSupportingLineShowsOnlyItsLabel() {
        rule.setContent {
            ApexTheme { SettingRow(Icons.Rounded.Place, label = "Ort", supporting = null) {} }
        }
        rule.onNodeWithText("Ort").assertIsDisplayed()
    }

    /**
     * The whole row is the target, not the switch alone. A switch is about 52 dp at the right edge
     * of a 360 dp row, and the label is the obvious thing to press.
     */
    @Test
    fun theWholeSwitchRowToggles() {
        var checked = false
        rule.setContent {
            ApexTheme {
                SwitchRow(
                    Icons.Rounded.Place, label = "Himmel animieren", supporting = null,
                    checked = checked, onChange = { checked = it },
                    modifier = Modifier.testTag("row"),
                )
            }
        }
        rule.onNodeWithText("Himmel animieren").performClick()
        assertTrue("pressing the label did not toggle the switch", checked)
    }

    @Test
    fun aNavRowShowsItsValueAndReports() {
        var tapped = false
        rule.setContent {
            ApexTheme {
                NavRow(
                    Icons.Rounded.Place, label = "Ort", value = "Meran",
                    onClick = { tapped = true }, modifier = Modifier.testTag("nav"),
                )
            }
        }
        rule.onNodeWithText("Meran").assertIsDisplayed()
        rule.onNodeWithTag("nav").performClick()
        assertTrue("the row did not report being pressed", tapped)
    }

    @Test
    fun aGroupShowsItsHeadingAboveItsContent() {
        rule.setContent {
            ApexTheme {
                SettingsGroup("ANZEIGE") { SettingRow(Icons.Rounded.Place, label = "Wind", supporting = null) {} }
            }
        }
        rule.onNodeWithText("ANZEIGE").assertIsDisplayed()
        rule.onNodeWithText("Wind").assertIsDisplayed()
    }
}
