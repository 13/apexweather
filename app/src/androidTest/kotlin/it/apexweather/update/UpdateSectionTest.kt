package it.apexweather.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The update row across its states. It drives the stateless row directly rather than the view
 * model, so nothing here reaches GitHub.
 */
class UpdateSectionTest {
    @get:Rule val rule = createComposeRule()

    private var clicked: String? = null

    private fun show(state: UpdateUiState) = rule.setContent {
        ApexTheme {
            UpdateRow(
                state = state,
                onCheck = { clicked = "check" },
                onDownload = { clicked = "download" },
                onInstall = { clicked = "install" },
                onGrantPermission = { clicked = "grant" },
                onRetryInstall = { clicked = "retry" },
                onOpenReleasePage = { clicked = "page" },
            )
        }
    }

    @Test fun idleOffersOnlyTheCheckButton() {
        show(UpdateUiState.Idle)
        rule.onNodeWithTag("update_action").assertIsDisplayed().performClick()
        assertEquals("check", clicked)
        rule.onNodeWithTag("update_status").assertDoesNotExist()
        rule.onNodeWithTag("update_release_page").assertDoesNotExist()
    }

    @Test fun anAvailableUpdateNamesItselfAndOffersTheDownload() {
        show(UpdateUiState.Available(AppVersion(0, 3, 0), 5_493_047, RELEASE_URL))
        rule.onNodeWithTag("update_status").assertIsDisplayed()
        rule.onNodeWithTag("update_action").performClick()
        assertEquals("download", clicked)
        // The page is the fallback that works even where installing is refused.
        rule.onNodeWithTag("update_release_page").assertIsDisplayed()
    }

    @Test fun aDownloadInFlightShowsProgressAndNoButton() {
        show(UpdateUiState.Downloading(AppVersion(0, 3, 0), 1_000, 4_000, RELEASE_URL))
        rule.onNodeWithTag("update_progress").assertIsDisplayed()
        rule.onNodeWithTag("update_action").assertDoesNotExist()
    }

    @Test fun aReadyDownloadOffersTheInstall() {
        show(UpdateUiState.Ready(AppVersion(0, 3, 0), File("/dev/null"), digestVerified = true, releaseUrl = RELEASE_URL))
        rule.onNodeWithTag("update_action").performClick()
        assertEquals("install", clicked)
    }

    @Test fun aRefusedInstallerPermissionExplainsItselfAndOffersTheShortcut() {
        show(UpdateUiState.NeedsPermission(RELEASE_URL))
        rule.onNodeWithTag("update_status").assertIsDisplayed()
        rule.onNodeWithTag("update_action").performClick()
        assertEquals("grant", clicked)
        rule.onNodeWithTag("update_release_page").assertIsDisplayed()
    }

    @Test fun aFailedCheckSaysSoAndLetsItBeTriedAgain() {
        show(UpdateUiState.Failed(UpdateFailure.NETWORK))
        rule.onNodeWithTag("update_status").assertIsDisplayed()
        rule.onNodeWithTag("update_action").performClick()
        assertEquals("check", clicked)
    }

    @Test fun theReleasePageCanBeOpenedWithoutInstalling() {
        show(UpdateUiState.Available(AppVersion(0, 3, 0), 1, RELEASE_URL))
        rule.onNodeWithTag("update_release_page").performClick()
        assertEquals("page", clicked)
    }

    private companion object { const val RELEASE_URL = "https://github.com/13/apexweather/releases/tag/v0.3.0" }
}
