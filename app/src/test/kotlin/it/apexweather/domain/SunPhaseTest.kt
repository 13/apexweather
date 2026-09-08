package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SunPhaseTest {
    private val sunrise = Instant.parse("2026-09-08T04:44:00Z") // 06:44 local
    private val sunset = Instant.parse("2026-09-08T17:41:00Z")  // 19:41 local

    @Test fun night() = assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T01:00:00Z"), sunrise, sunset, ROME))
    @Test fun dawn() = assertEquals(SunPhase.DAWN, SunPhaseCalculator.phase(Instant.parse("2026-09-08T04:30:00Z"), sunrise, sunset, ROME))
    @Test fun day() = assertEquals(SunPhase.DAY, SunPhaseCalculator.phase(Instant.parse("2026-09-08T10:00:00Z"), sunrise, sunset, ROME))
    @Test fun dusk() = assertEquals(SunPhase.DUSK, SunPhaseCalculator.phase(Instant.parse("2026-09-08T18:00:00Z"), sunrise, sunset, ROME))
    @Test fun `late evening is night`() = assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T20:00:00Z"), sunrise, sunset, ROME))

    @Test fun `fallback without sun times uses 07 to 19 local`() {
        assertEquals(SunPhase.DAY, SunPhaseCalculator.phase(Instant.parse("2026-09-08T10:00:00Z"), null, null, ROME))
        assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T22:00:00Z"), null, null, ROME))
    }
}
