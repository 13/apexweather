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

    /**
     * The one question an ensemble is built to answer, and the app was paying for the members and
     * reading only their temperatures.
     *
     * Every other probability here is an average of what several deterministic models each *claim*
     * the chance is. This is a count: how many of fifty equally plausible atmospheres actually got
     * wet. It costs 225 B gzipped on ICON-D2's two days and 3 238 B on ECMWF's fifteen, measured
     * against the same calls without it.
     */
    @Test
    fun `the wet share is the fraction of members that rained`() {
        val spread = EnsembleMapper.map(
            Fixtures.json.decodeFromString(EnsembleResponse.serializer(), Fixtures.read("openmeteo_ensemble_ecmwf.json")),
            fetchedAt,
        )
        assertEquals(50, spread.memberCount)
        assertTrue("the recorded run has wet hours in it", spread.wetShareByEpochSecond.isNotEmpty())
        spread.wetShareByEpochSecond.values.forEach {
            assertTrue("a share is a fraction, not a percentage: $it", it in 0.0..1.0)
        }
        // Fifty members, so every share is a fiftieth of something and never an arbitrary real.
        spread.wetShareByEpochSecond.values.forEach {
            assertEquals(0.0, (it * 50).let { n -> n - Math.round(n) }.toDouble(), 1e-9)
        }
    }

    /** An hour the ensemble does not reach has no share at all, rather than a share of zero. */
    @Test
    fun `an hour beyond the run is absent rather than dry`() {
        val spread = EnsembleMapper.map(
            Fixtures.json.decodeFromString(EnsembleResponse.serializer(), Fixtures.read("openmeteo_ensemble.json")),
            fetchedAt,
        )
        val last = spread.wetShareByEpochSecond.keys.max()
        assertNull(spread.wetShareAt(java.time.Instant.ofEpochSecond(last + 3600 * 24 * 30)))
    }

    /**
     * For the chance of rain the two ensembles pool, one vote each, where both reach. On
     * 2026-09-16 over Dorf Tirol ICON-D2's run was late: 15 % of its members were wet at 19:00
     * where 100 % of ECMWF's were, ten of eleven deterministic models were raining and the
     * province said 65 %. Letting one model family's members stand for the whole chance put 16 %
     * under 2,3 mm on the strip.
     */
    @Test
    fun `combining pools the two ensembles' wet shares where both reach`() {
        val near = EnsembleSpread(fetchedAt, 20, mapOf(1L to 1.0, 3L to 1.0), mapOf(1L to 0.2, 3L to 0.4))
        val far = EnsembleSpread(fetchedAt, 50, mapOf(1L to 5.0, 2L to 5.0), mapOf(1L to 0.9, 2L to 0.6))
        val combined = EnsembleSpread.combine(near, far)!!
        assertEquals(0.55, combined.wetShareByEpochSecond.getValue(1L), 1e-9)
        assertEquals("the far one alone past the near one's reach", 0.6, combined.wetShareByEpochSecond.getValue(2L), 1e-9)
        assertEquals("the near one alone where the far one is missing", 0.4, combined.wetShareByEpochSecond.getValue(3L), 1e-9)
        assertEquals("the spread still prefers the near ensemble", 1.0, combined.halfWidthByEpochSecond.getValue(1L), 1e-9)
    }
}
