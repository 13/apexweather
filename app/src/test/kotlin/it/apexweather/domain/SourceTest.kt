package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTest {

    /**
     * The two sources that report a real run time are both *old* the moment they can first be seen,
     * so their thresholds have to be cadence plus publication lag plus margin — anything tighter
     * throws them out of the consensus for being what they always are.
     *
     * Both numbers were measured on 2026-09-11 and both were wrong before that. GeoSphere's own
     * metadata lists three-hourly reference times and the newest on offer at 15:22 UTC was 09:00 —
     * six hours and twenty-four minutes old against a six-hour threshold. SIAG KMOS carried run
     * 02:00 with a file created at 13:00, an eleven-hour lag, against a sixteen-hour threshold.
     */
    @Test
    fun `a source with a real run time outlives its own publication lag`() {
        // 3 h cadence + up to ~6,5 h before the next run supersedes it.
        assertTrue(
            "AROME goes stale inside its own publication cycle",
            Source.GEOSPHERE_AROME.staleAfterHours >= 7,
        )
        // 12 h cadence + an 11 h lag: the newest run reaches 23 h old before the next one lands.
        assertTrue(
            "KMOS goes stale while it is still the newest run there is",
            Source.SIAG_KMOS.staleAfterHours >= 24,
        )
    }

    @Test
    fun `staleAfterHours is per source`() {
        assertEquals(26, Source.SIAG_KMOS.staleAfterHours)
        assertEquals(10, Source.GEOSPHERE_AROME.staleAfterHours)
        assertEquals(12, Source.ECMWF.staleAfterHours)
        assertEquals(6, Source.ICON_D2.staleAfterHours)
    }

    /**
     * The Open-Meteo eight report no run time, so their stamp is the fetch and the threshold only
     * has to outlast a refresh interval. Only the two that do report one need the lag reasoning.
     */
    @Test
    fun `only the sources with a real run time carry a long threshold`() {
        Source.entries.filterNot { it.hasRunTime }.forEach {
            assertTrue("${it.name} is stamped with its fetch time, not a run", it.staleAfterHours <= 12)
        }
        assertEquals(listOf(Source.SIAG_KMOS, Source.GEOSPHERE_AROME), Source.entries.filter { it.hasRunTime })
    }

    @Test
    fun `every source has a positive stale threshold`() {
        assertEquals(emptyList<Source>(), Source.entries.filter { it.staleAfterHours <= 0 })
    }
}
