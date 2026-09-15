package it.apexweather.ui.compare

import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.ModelBias
import it.apexweather.domain.STERZING
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDetailStateBuilderTest {

    private val present = listOf(
        Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2,
        Source.GEOSPHERE_AROME, Source.ECMWF,
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(
        forecasts = present.associateWith { s -> forecast(s, (0 until if (s == Source.ECMWF) 240 else 48).map { point(it, 12.0) }) },
        status = present.associateWith { SourceStatus.Ok(hour(0)) },
        modelBias = ModelBias(mapOf(Source.ICON_CH1 to mapOf(DayPart.AFTERNOON to mapOf(LeadBucket.SIX to 1.4, LeadBucket.NOW to 9.9)))),
    )

    @Test
    fun `an ICON run shares its vote with the other three`() {
        val s = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(2))
        assertEquals(0.5, s.weight!!, 1e-9)
        assertEquals(4, s.familySize)
        assertTrue(s.inConsensus)
        assertFalse(s.onlyFillsGaps)
    }

    @Test
    fun `AROME is the only one of its core and has a whole vote`() {
        val s = SourceDetailStateBuilder.build(Source.GEOSPHERE_AROME, snapshot, DORF_TIROL, hour(2))
        assertEquals(1.0, s.weight!!, 1e-9)
        assertEquals(1, s.familySize)
    }

    /** The blender drops globals hour by hour where two regional runs reach; the sheet says so. */
    @Test
    fun `a global only fills gaps while two regional sources are in the blend`() {
        val s = SourceDetailStateBuilder.build(Source.ECMWF, snapshot, DORF_TIROL, hour(2))
        assertTrue(s.inConsensus)
        assertTrue(s.onlyFillsGaps)
        val alone = snapshot.copy(forecasts = snapshot.forecasts.filterKeys { it == Source.ECMWF || it == Source.GEOSPHERE_AROME })
        assertFalse(SourceDetailStateBuilder.build(Source.ECMWF, alone, DORF_TIROL, hour(2)).onlyFillsGaps)
    }

    @Test
    fun `a stale source is not in the consensus and has no weight`() {
        val stale = snapshot.copy(status = snapshot.status + (Source.ICON_D2 to SourceStatus.Stale(hour(-20))))
        val s = SourceDetailStateBuilder.build(Source.ICON_D2, stale, DORF_TIROL, hour(2))
        assertFalse(s.inConsensus)
        assertNull(s.weight)
        assertEquals(6, s.staleAfterHours)
    }

    /** Reach is counted from what the app holds, never from provider metadata. */
    @Test
    fun `reach is the last hour with a value`() {
        assertEquals(hour(239), SourceDetailStateBuilder.build(Source.ECMWF, snapshot, DORF_TIROL, hour(2)).reachUntil)
        assertEquals(hour(47), SourceDetailStateBuilder.build(Source.ICON_D2, snapshot, DORF_TIROL, hour(2)).reachUntil)
    }

    @Test
    fun `the station block shows the six-hour error per part of the day`() {
        val block = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(2)).station as StationBlock.Checked
        assertEquals("Meran", block.stationName)
        assertEquals(DayPart.entries, block.cells.map { it.part })
        assertEquals(1.4, block.cells.single { it.part == DayPart.AFTERNOON }.kelvin!!, 1e-9)
        assertNull("too few hours is null, not zero", block.cells.single { it.part == DayPart.NIGHT }.kelvin)
    }

    @Test
    fun `KMOS can never be checked, and a place without a station has no block`() {
        assertEquals(StationBlock.NeverCheckable, SourceDetailStateBuilder.build(Source.SIAG_KMOS, snapshot, DORF_TIROL, hour(2)).station)
        assertEquals(StationBlock.NoStation, SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, STERZING, hour(2)).station)
        assertEquals(StationBlock.NoStation, SourceDetailStateBuilder.build(Source.SIAG_KMOS, snapshot, null, hour(2)).station)
    }

    @Test
    fun `a source never loaded has facts and nothing else`() {
        val s = SourceDetailStateBuilder.build(Source.GEM, snapshot, DORF_TIROL, hour(2))
        assertNull(s.status)
        assertNull(s.reachUntil)
        assertNull(s.weight)
        assertFalse(s.inConsensus)
        assertEquals("ECCC", s.info.provider)
    }
}
