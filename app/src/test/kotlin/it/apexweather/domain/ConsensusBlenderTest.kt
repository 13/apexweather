package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusBlenderTest {
    private val blender = ConsensusBlender(ROME)

    @Test
    fun `median temperature and min max band from regional sources`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 12.0))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(12.0, h.tempC, 0.0)
        assertEquals(10.0, h.tempMinC, 0.0)
        assertEquals(14.0, h.tempMaxC, 0.0)
        assertEquals(3, h.sourceCount)
    }

    @Test
    fun `even count uses mean of the two middle values`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 20.0))),
        )
        assertEquals(15.0, blender.blend(f).hourly.single().tempC, 0.0)
    }

    @Test
    fun `ECMWF excluded when two or more regional sources cover the hour`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 12.0))),
            Source.ECMWF to forecast(Source.ECMWF, listOf(point(0, 30.0))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(11.0, h.tempC, 0.0)
        assertEquals(2, h.sourceCount)
    }

    @Test
    fun `ECMWF included when fewer than two regional sources cover the hour`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ECMWF to forecast(Source.ECMWF, listOf(point(0, 30.0), point(1, 31.0))),
        )
        val hours = blender.blend(f).hourly
        assertEquals(20.0, hours[0].tempC, 0.0)
        assertEquals(2, hours[0].sourceCount)
        assertEquals(31.0, hours[1].tempC, 0.0)
        assertEquals(1, hours[1].sourceCount)
    }

    @Test
    fun `precip probability is max of model probabilities`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, prob = 20))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, prob = 60))),
        )
        assertEquals(60, blender.blend(f).hourly.single().precipProb)
    }

    @Test
    fun `precip probability falls back to share of models with rain`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, precip = 0.5))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, precip = 0.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, precip = 1.2))),
        )
        assertEquals(67, blender.blend(f).hourly.single().precipProb)
    }

    @Test
    fun `condition majority vote with severity tie-break`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, condition = Condition.RAIN))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, condition = Condition.RAIN))),
        )
        assertEquals(Condition.RAIN, blender.blend(f).hourly.single().condition)
    }

    @Test
    fun `agreement drops with spread and is 0_5 for a single source`() {
        val tight = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 11.5))),
        )
        assertEquals(0.75f, blender.blend(tight).hourly.single().agreement, 1e-6f)
        val wide = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 20.0))),
        )
        assertEquals(0f, blender.blend(wide).hourly.single().agreement, 1e-6f)
        val single = mapOf(Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))))
        val h = blender.blend(single).hourly.single()
        assertEquals(0.5f, h.agreement, 1e-6f)
        assertEquals(h.tempC, h.tempMinC, 0.0)
        assertEquals(h.tempC, h.tempMaxC, 0.0)
    }

    @Test
    fun `gust is max, wind is median`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, wind = 10.0, gust = 30.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, wind = 20.0, gust = 50.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, wind = 12.0, gust = null))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(12.0, h.windKmh!!, 0.0)
        assertEquals(50.0, h.gustKmh!!, 0.0)
    }

    /**
     * KMOS publishes no wind. Counting its absence as a calm 0.0 used to drag the median down at
     * every third hour, which is where the consensus wind was read from.
     */
    @Test
    fun `a model without wind is left out of the wind median, not counted as calm`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, wind = 20.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, wind = 22.0))),
            Source.SIAG_KMOS to forecast(Source.SIAG_KMOS, listOf(point(0, 10.0, wind = null))),
        )
        assertEquals(21.0, blender.blend(f).hourly.single().windKmh!!, 0.0)
    }

    /** With nothing to average, the consensus has no wind to report rather than a made-up zero. */
    @Test
    fun `wind is absent when no model publishes it`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, wind = null))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, wind = null))),
        )
        assertNull(blender.blend(f).hourly.single().windKmh)
    }

    @Test
    fun `daily is aggregated from consensus hourly`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0 + it, precip = 1.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 12.0 + it, precip = 3.0) }),
        )
        val days = blender.blend(f).daily
        assertTrue(days.isNotEmpty())
        val d0 = days[0]
        assertEquals(11.0, d0.minC, 0.0)
        assertEquals(32.0, d0.maxC, 0.0)
        assertEquals(44.0, d0.precipMm, 1e-9) // 22 local hours on 2026-09-08 × median 2.0
    }

    /**
     * The median everywhere else, the maximum here, and deliberately so: a gust nobody was warned
     * about is worse than one that did not arrive. Pinned because it reads like an oversight.
     */
    @Test
    fun `the gust is the worst any model expects, not the middle one`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, gust = 30.0))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, gust = 35.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, gust = 95.0))),
        )
        assertEquals(95.0, blender.blend(f).hourly.single().gustKmh!!, 0.0)
    }

    @Test
    fun `the freezing level is the median of the models that publish one`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, freezing = 2000.0))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, freezing = 2400.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, freezing = 3100.0))),
        )
        assertEquals(2400.0, blender.blend(f).hourly.single().freezingLevelM!!, 0.0)
    }

    /** ECMWF publishes none. A model that says nothing must not pull the snow line to sea level. */
    @Test
    fun `a model without a freezing level is left out of it rather than counted as zero`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, freezing = 2000.0))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, freezing = 2200.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, freezing = null))),
        )
        assertEquals(2100.0, blender.blend(f).hourly.single().freezingLevelM!!, 0.0)
    }

    @Test
    fun `no model publishing a freezing level leaves it absent`() {
        val f = mapOf(Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))))
        assertNull(blender.blend(f).hourly.single().freezingLevelM)
    }

    @Test
    fun `the lowest hour of the day is what the day reports as its snow line`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0, freezing = 3000.0 - it * 50) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 10.0, freezing = 3000.0 - it * 50) }),
        )
        val day = blender.blend(f).daily.first()
        // 22 local hours of 2026-09-08 fall in the first day; the last of them is the lowest.
        assertEquals(1950.0, day.freezingLevelMinM!!, 0.0)
    }

    /**
     * Days past the reach of the regional models are ECMWF alone. The day has to carry that, because
     * a badge reading "50 % agreement" over a single model invents a comparison that never happened.
     */
    @Test
    fun `a day only one model reaches says so`() {
        val f = mapOf(Source.ECMWF to forecast(Source.ECMWF, (0 until 24).map { point(it, 10.0) }))
        assertEquals(1, blender.blend(f).daily.first().sourceCount)
    }

    @Test
    fun `a day every model reaches counts them all`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0) }),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, (0 until 24).map { point(it, 11.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 12.0) }),
        )
        assertEquals(3, blender.blend(f).daily.first().sourceCount)
    }

    @Test
    fun `empty input yields empty consensus`() {
        val c = blender.blend(emptyMap())
        assertTrue(c.hourly.isEmpty())
        assertTrue(c.daily.isEmpty())
    }

    private fun minutes(vararg mm: Double) = mm.mapIndexed { i, v ->
        it.apexweather.domain.model.MinutePoint(T0.plusSeconds(i * 900L), v)
    }

    @Test
    fun `the quarter-hourly series is the median of the models that publish one`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.0, 1.0)),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.4, 2.0)),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.2, 3.0)),
        )
        val minutely = blender.blend(f).minutely
        assertEquals(3, minutely.size)
        assertEquals(0.2, minutely[1].precipMm, 1e-9)
        assertEquals(3, minutely[1].sourceCount)
    }

    /** A model with no quarter-hourly series simply does not vote; it must not count as a dry zero. */
    @Test
    fun `a model without a quarter-hourly series is left out of it`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))).copy(minutely = minutes(1.0)),
            Source.ECMWF to forecast(Source.ECMWF, listOf(point(0, 10.0))),
        )
        val minutely = blender.blend(f).minutely
        assertEquals(1, minutely.single().sourceCount)
        assertEquals(1.0, minutely.single().precipMm, 0.0)
    }

    @Test
    fun `the start of precipitation is found to the quarter-hour`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.0, 0.6, 1.0)),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.0, 0.8, 1.0)),
        )
        assertEquals(T0.plusSeconds(2 * 900L), blender.blend(f).precipitationStartsAt(T0))
    }

    /** Already raining: a start time would be a lie, and the reader can see it out of the window. */
    @Test
    fun `no start time is offered while it is already raining`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))).copy(minutely = minutes(1.0, 1.0, 1.0)),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0))).copy(minutely = minutes(1.0, 1.0, 1.0)),
        )
        assertNull(blender.blend(f).precipitationStartsAt(T0.plusSeconds(450L)))
    }

    @Test
    fun `a dry twelve hours offers no start time`() {
        val f = mapOf(Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))).copy(minutely = minutes(0.0, 0.0, 0.0)))
        assertNull(blender.blend(f).precipitationStartsAt(T0))
    }
}
