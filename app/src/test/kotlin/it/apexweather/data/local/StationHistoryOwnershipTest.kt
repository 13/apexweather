package it.apexweather.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which thermometer a `station_history` row speaks for.
 *
 * The rule is small and the consequence of getting it wrong is not: BiasCorrector subtracts each
 * model's habit *at the station*, and a place can now change station. Two thermometers 264 m apart
 * averaged into one habit is worse than no habit, and it has no symptom on screen — the hero is
 * simply quietly worse.
 */
class StationHistoryOwnershipTest {
    private fun row(station: String?) = StationHistoryEntity(
        place = "021101", hourEpoch = 1789830000L, observedC = 18.5,
        modelsJson = "{}", station = station,
    )

    @Test
    fun `a row names the station it came from`() {
        assertTrue(row("ITIROL16").belongsTo("ITIROL16", provincial = "23200MS"))
        assertFalse(row("ITIROL16").belongsTo("23200MS", provincial = "23200MS"))
    }

    /**
     * Null is "the provincial station", not "unknown". Every row written before version 3 is about
     * the provincial thermometer, because that is the only one this app had ever read — treating
     * them as unknown would throw away every reader's accumulated evidence on upgrade.
     */
    @Test
    fun `a row written before the column belongs to the provincial station`() {
        assertTrue(row(null).belongsTo("23200MS", provincial = "23200MS"))
        assertFalse(row(null).belongsTo("ITIROL16", provincial = "23200MS"))
    }

    /** A place with no station at all owns nothing, and an old row is not claimed by default. */
    @Test
    fun `no station claims no rows`() {
        assertFalse(row(null).belongsTo(null, provincial = null))
        assertFalse(row("ITIROL16").belongsTo(null, provincial = "23200MS"))
    }
}
