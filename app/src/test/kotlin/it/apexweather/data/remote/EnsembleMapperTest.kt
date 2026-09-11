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

    /**
     * ECMWF's own ensemble, recorded from the same endpoint on 2026-09-11. It is here for the far
     * end of the day list, where ICON-D2 has long stopped and only one deterministic model reaches.
     */
    @Test
    fun `the ECMWF ensemble reaches a fortnight with fifty members`() {
        val ecmwf = EnsembleMapper.map(
            Fixtures.json.decodeFromString(
                EnsembleResponse.serializer(),
                Fixtures.read("openmeteo_ensemble_ecmwf.json"),
            ),
            fetchedAt,
        )
        assertEquals(50, ecmwf.memberCount)
        assertTrue("only ${ecmwf.halfWidthByEpochSecond.size} hours", ecmwf.halfWidthByEpochSecond.size >= 14 * 24)
        assertTrue(ecmwf.halfWidthByEpochSecond.values.all { it >= 0.0 && it < 20.0 })
        // Uncertainty grows with lead time: next Thursday is a wider claim than tomorrow.
        val byHour = ecmwf.halfWidthByEpochSecond.toSortedMap().values.toList()
        assertTrue(byHour.take(24).average() < byHour.takeLast(24).average())
    }

    private fun spread(vararg hours: Pair<Long, Double>, members: Int = 20) =
        EnsembleSpread(fetchedAt, members, hours.toMap())

    /**
     * ICON-D2 at 2 km says more about this valley tomorrow than ECMWF at 25 km does, and ECMWF's
     * fifty members are the only thing that says anything at all about next Thursday. So the near
     * ensemble wins every hour both cover and the far one carries the rest.
     */
    @Test
    fun `the near ensemble wins the hours it reaches and the far one carries the rest`() {
        val near = spread(1L to 0.4, 2L to 0.5)
        val far = spread(1L to 2.0, 2L to 2.2, 3L to 2.5, members = 50)
        val combined = EnsembleSpread.combine(near, far)!!
        assertEquals(0.4, combined.halfWidthByEpochSecond[1L]!!, 1e-9)
        assertEquals(0.5, combined.halfWidthByEpochSecond[2L]!!, 1e-9)
        assertEquals(2.5, combined.halfWidthByEpochSecond[3L]!!, 1e-9)
    }

    /** Either ensemble on its own is still an ensemble; both failing is what leaves nothing. */
    @Test
    fun `one ensemble missing leaves the other in charge`() {
        val near = spread(1L to 0.4)
        val far = spread(1L to 2.0, 9L to 2.5, members = 50)
        assertEquals(far, EnsembleSpread.combine(null, far))
        assertEquals(near, EnsembleSpread.combine(near, null))
        assertNull(EnsembleSpread.combine(null, null))
    }

    /**
     * A response that parsed but carried no members is not an ensemble that reaches every hour with
     * nothing to say — it is nothing, and must not shadow the one that does reach.
     */
    @Test
    fun `an empty near ensemble does not shadow the far one`() {
        val far = spread(1L to 2.0, members = 50)
        assertEquals(far, EnsembleSpread.combine(spread(members = 0), far))
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
