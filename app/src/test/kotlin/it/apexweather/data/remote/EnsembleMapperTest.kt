package it.apexweather.data.remote

import it.apexweather.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Against `openmeteo_ensemble.json`, recorded from the live ensemble endpoint on 2026-09-10.
 *
 * The spread between several models is a proxy for uncertainty; an ensemble is the thing itself —
 * one model run many times from starting points nudged within the error bars of what was observed.
 */
class EnsembleMapperTest {

    private val fetchedAt: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val spread = EnsembleMapper.map(
        Fixtures.json.decodeFromString(EnsembleResponse.serializer(), Fixtures.read("openmeteo_ensemble.json")),
        fetchedAt,
    )

    @Test
    fun `the members are counted and the hours are covered`() {
        assertTrue("only ${spread.memberCount} members", spread.memberCount >= 15)
        assertTrue(spread.halfWidthByEpochSecond.isNotEmpty())
    }

    /** A half-width in degrees. Negative would mean the percentiles came out backwards. */
    @Test
    fun `every spread is a plausible half-width`() {
        assertTrue(spread.halfWidthByEpochSecond.values.all { it >= 0.0 && it < 15.0 })
    }

    @Test
    fun `an hour outside the run has no spread rather than a zero`() {
        assertNull(spread.halfWidthAt(fetchedAt.plusSeconds(30L * 24 * 3600)))
    }

    /** An empty response is a source that said nothing, not an ensemble of perfect agreement. */
    @Test
    fun `a response with no members yields no spread`() {
        val empty = EnsembleResponse(hourly = kotlinx.serialization.json.JsonObject(emptyMap()))
        val mapped = EnsembleMapper.map(empty, fetchedAt)
        assertEquals(0, mapped.memberCount)
        assertTrue(mapped.halfWidthByEpochSecond.isEmpty())
    }
}
