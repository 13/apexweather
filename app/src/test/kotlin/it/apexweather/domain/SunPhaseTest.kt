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

    /**
     * The two kinds of sun time answer two different questions, and the phase needs both.
     *
     * The terrain times bound the *day*: it is not daytime here while the village sits in the shadow
     * of the Texelgruppe, which in Dorf Tirol is 70 minutes each morning and 54 each evening in
     * September. The astronomical times bound the *night*: the sky overhead is not dark the moment
     * the sun goes behind a mountain, and this app draws a sky. Using either alone paints the screen
     * wrong, in opposite directions.
     */
    @Test
    fun `the ridge bounds the day and the ephemeris bounds the night`() {
        val zone = SouthTyrol.ZONE
        val sunrise = Instant.parse("2026-09-13T04:54:00Z")
        val sunset = Instant.parse("2026-09-13T17:28:00Z")
        val ridgeUp = sunrise.plusSeconds(70 * 60)
        val ridgeDown = sunset.minusSeconds(54 * 60)
        fun at(t: Instant) = SunPhaseCalculator.phase(t, sunrise, sunset, zone, ridgeUp, ridgeDown)

        // Before the sun is on the village it is not day, however far up the ephemeris has it.
        assertEquals(SunPhase.DAWN, at(sunrise.plusSeconds(30 * 60)))
        assertEquals(SunPhase.DAY, at(ridgeUp.plusSeconds(60)))
        // And once it has gone behind the ridge it is dusk — but not night, because the sky is lit
        // for the better part of an hour more.
        assertEquals(SunPhase.DAY, at(ridgeDown.minusSeconds(60)))
        assertEquals(SunPhase.DUSK, at(ridgeDown.plusSeconds(60)))
        assertEquals(SunPhase.DUSK, at(sunset.plusSeconds(30 * 60)))
        assertEquals(SunPhase.NIGHT, at(sunset.plusSeconds(60 * 60)))
        assertEquals(SunPhase.NIGHT, at(sunrise.minusSeconds(60 * 60)))
    }

    /** A place with no ridge worth the name behaves exactly as it did before. */
    @Test
    fun `terrain times that match the ephemeris change nothing`() {
        val zone = SouthTyrol.ZONE
        val sunrise = Instant.parse("2026-09-13T04:54:00Z")
        val sunset = Instant.parse("2026-09-13T17:28:00Z")
        listOf(
            sunrise.minusSeconds(3600), sunrise, sunrise.plusSeconds(1800),
            sunrise.plusSeconds(4 * 3600), sunset.minusSeconds(600), sunset.plusSeconds(1800),
            sunset.plusSeconds(4000),
        ).forEach { t ->
            assertEquals(
                SunPhaseCalculator.phase(t, sunrise, sunset, zone),
                SunPhaseCalculator.phase(t, sunrise, sunset, zone, sunrise, sunset),
            )
        }
    }

    /** And a catalogue with no skyline in it falls back to exactly the old rule. */
    @Test
    fun `no terrain times means the astronomical rule`() {
        val zone = SouthTyrol.ZONE
        val sunrise = Instant.parse("2026-09-13T04:54:00Z")
        val sunset = Instant.parse("2026-09-13T17:28:00Z")
        val noon = Instant.parse("2026-09-13T11:00:00Z")
        assertEquals(
            SunPhaseCalculator.phase(noon, sunrise, sunset, zone),
            SunPhaseCalculator.phase(noon, sunrise, sunset, zone, null, null),
        )
    }
}
