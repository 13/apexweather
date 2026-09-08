package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTest {

    @Test
    fun `staleAfterHours is per source`() {
        assertEquals(16, Source.SIAG_KMOS.staleAfterHours) // two runs per day, 12 h apart, plus margin
        assertEquals(12, Source.ECMWF.staleAfterHours)
        assertEquals(6, Source.ICON_D2.staleAfterHours)
    }

    @Test
    fun `every source has a positive stale threshold`() {
        assertEquals(emptyList<Source>(), Source.entries.filter { it.staleAfterHours <= 0 })
    }
}
