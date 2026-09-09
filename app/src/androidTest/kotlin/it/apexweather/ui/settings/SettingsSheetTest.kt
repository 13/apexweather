package it.apexweather.ui.settings

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.BuildConfig
import it.apexweather.data.AppSettings
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsSheetTest {
    @get:Rule val rule = createComposeRule()

    private fun show() = rule.setContent {
        ApexTheme {
            SettingsSheet(
                settings = AppSettings(),
                onLanguage = {}, onWindUnit = {}, onAnimations = {}, onRefresh = {}, onDismiss = {},
            )
        }
    }

    private fun textUnder(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }) +
            node.children.joinToString(" ") { textUnder(it) }

    /** The build line is what a bug report quotes, so it has to name this exact build. */
    @Test
    fun theBuildLineNamesTheVersionAndTheCommitItCameFrom() {
        show()
        rule.onNodeWithTag("about_build").assertIsDisplayed()
        val line = textUnder(rule.onNodeWithTag("about_build").fetchSemanticsNode())

        listOf(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toString(),
            BuildConfig.BUILD_TYPE,
            BuildConfig.GIT_HASH,
            BuildConfig.GIT_DATE,
        ).forEach { assertTrue("build line is missing '$it': $line", line.contains(it)) }
    }
}
