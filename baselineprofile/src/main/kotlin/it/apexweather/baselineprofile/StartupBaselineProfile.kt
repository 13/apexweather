package it.apexweather.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the classes and methods the app actually runs, so ART can compile them ahead of time
 * instead of interpreting them on the reader's first launch.
 *
 * Two journeys, not one, and the split is the point. The startup profile also reorders the dex so
 * the launch path is read in one sweep, and that only helps if it contains the launch path and
 * nothing else — marking the whole tour `includeInStartupProfile` produced a startup profile
 * byte-identical to the baseline one, which reorders nothing. So the cold start is its own journey,
 * and the tab tour contributes to the baseline profile alone.
 *
 * Generate both with `./gradlew :app:generateReleaseBaselineProfile` on a device running API 33 or
 * newer; no root is needed there because a release build is profileable by default.
 */
class StartupBaselineProfile {

    @get:Rule val rule = BaselineProfileRule()

    /** Cold start up to the first forecast on screen: Compose, Hilt, Room, the consensus blend. */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // The forecast arrives over the network, so the first frames are the empty state; waiting for
        // the location text means the profile covers the path that actually renders data.
        device.wait(Until.hasObject(By.textContains("Dorf Tirol")), 15_000)
    }

    /** The rest of the app, for the baseline profile only — none of it runs during a cold start. */
    @Test
    fun tabs() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Dorf Tirol")), 15_000)
        listOf("Vergleich", "Bericht", "Heute").forEach { tab ->
            device.findObject(By.text(tab))?.click()
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "it.apexweather"
    }
}
