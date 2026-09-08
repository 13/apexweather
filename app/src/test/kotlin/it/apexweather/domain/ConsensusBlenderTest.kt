package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusBlenderTest {
    private val blender = ConsensusBlender(ROME)

    @Test
    fun `staleAfterHours is per source`() {
        assertEquals(14, Source.SIAG_KMOS.staleAfterHours)
        assertEquals(12, Source.ECMWF.staleAfterHours)
        assertEquals(6, Source.ICON_D2.staleAfterHours)
    }

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
        assertEquals(12.0, h.windKmh, 0.0)
        assertEquals(50.0, h.gustKmh!!, 0.0)
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

    @Test
    fun `empty input yields empty consensus`() {
        val c = blender.blend(emptyMap())
        assertTrue(c.hourly.isEmpty())
        assertTrue(c.daily.isEmpty())
    }
}
