package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusBlenderTest {
    private val blender = ConsensusBlender(ROME)

    /**
     * A bias of [c] for [source] at every part of the day and every lead time, so a test about the
     * correction is not also a test about which six hours or which lead it was measured in.
     */
    private fun bias(source: Source, c: Double) =
        ModelBias(mapOf(source to DayPart.entries.associateWith { LeadBucket.entries.associateWith { c } }))

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
    fun `precip probability is the mean of the model probabilities`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, prob = 20))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, prob = 60))),
        )
        assertEquals(40, blender.blend(f).hourly.single().precipProb)
    }

    /**
     * The reason it is not the maximum: one model seeing a shower is not the hour being likely to
     * rain, and it used to be allowed to say so on its own. Five models at zero and three at 60 is
     * a 23 % hour, not a 60 % one.
     */
    @Test
    fun `one alarmed model does not set the chance on its own`() {
        val wet = (0 until 3).map { Source.entries[it] to 60 }
        val dry = (3 until 8).map { Source.entries[it] to 0 }
        val f = (wet + dry).associate { (source, prob) ->
            source to forecast(source, listOf(point(0, 10.0, prob = prob)))
        }
        assertEquals(23, blender.blend(f).hourly.single().precipProb)
    }

    /**
     * And the reason it is not the median either, which is the same reason the amount is a mean:
     * once half the models are dry the median is zero and every model that sees the shower is
     * thrown away.
     */
    @Test
    fun `a minority of wet models still moves the chance off zero`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, prob = 80))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, prob = 0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, prob = 0))),
        )
        assertEquals(27, blender.blend(f).hourly.single().precipProb)
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

    /**
     * The wet models carry an amount, because a model saying RAIN at 0 mm is the contradiction the
     * blender now refuses outright — see `an hour that amounts to nothing is not called rain`.
     */
    @Test
    fun `condition majority vote with severity tie-break`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, precip = 1.0, condition = Condition.RAIN))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, precip = 1.0, condition = Condition.RAIN))),
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

    /**
     * The point of measuring a model's habit: a run that has been two degrees warm at the station
     * all week has those two degrees taken off before it is compared with anyone else.
     */
    @Test
    fun `a model's measured bias is taken off before it votes`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0))),
        )
        val uncorrected = blender.blend(f).hourly.single().tempC
        val corrected = blender.blend(f, bias(Source.ICON_D2, 4.0), now = T0).hourly.single().tempC
        assertEquals(10.0, uncorrected, 0.0)
        // ICON-D2 comes back to 10 too, so the band closes rather than the median moving.
        assertEquals(10.0, corrected, 0.0)
        assertEquals(10.0, blender.blend(f, bias(Source.ICON_D2, 4.0), now = T0).hourly.single().tempMaxC, 0.0)
    }

    /**
     * The habit holds out to where the record reaches and fades past it, so the far end of a
     * two-day strip is left closer to what the model actually wrote.
     */
    @Test
    fun `the correction has faded by the far end of the run`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 10.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 14.0) }),
        )
        val hourly = blender.blend(f, bias(Source.ICON_D2, 4.0), now = T0).hourly
        // Within the verified range the whole four degrees comes off, so both models say 10.
        assertEquals(10.0, hourly.first { it.time == hour(12) }.tempMaxC, 0.0)
        // Past a full day nothing does, and ICON-D2 is left as it wrote itself.
        assertEquals(14.0, hourly.first { it.time == hour(30) }.tempMaxC, 0.0)
    }

    /**
     * The habit belongs to the hour being forecast, not to the hour the app is looking.
     *
     * A model measured two degrees warm in the afternoon must have those two degrees taken off
     * tomorrow afternoon whenever the reader opens the app — and nothing taken off tonight, where
     * it was never measured.
     */
    @Test
    fun `the bias is read at the part of the day of the hour being blended`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 14.0) }),
        )
        // T0 is 02:00 local, so hour 1 is the night and hour 11 is the afternoon.
        val nightOnly = ModelBias(
            mapOf(Source.ICON_D2 to mapOf(DayPart.NIGHT to LeadBucket.entries.associateWith { 4.0 })),
        )
        val hourly = blender.blend(f, nightOnly, now = T0).hourly
        assertEquals(DayPart.NIGHT, DayPart.of(hour(1), ROME))
        assertEquals(DayPart.AFTERNOON, DayPart.of(hour(11), ROME))
        // Corrected in the night, where it was measured: ICON-D2 comes back to 10 and the band closes.
        assertEquals(10.0, hourly.first { it.time == hour(1) }.tempMaxC, 0.0)
        // Untouched in the afternoon, where nothing was measured.
        assertEquals(14.0, hourly.first { it.time == hour(11) }.tempMaxC, 0.0)
    }

    @Test
    fun `an empty bias map changes nothing`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
        )
        assertEquals(blender.blend(f).hourly.single().tempC, blender.blend(f, ModelBias.NONE, T0).hourly.single().tempC, 0.0)
    }

    /**
     * The afternoon of 2026-09-11, and what a plurality over labels costs.
     *
     * Six regional models: three called the hour overcast, two partly cloudy, one clear. Voted as
     * words that is three to two to one and the hour reads "Bedeckt". The cloud they actually
     * published was 100, 100, 87, 71, 61 and 14 per cent — a median of 79, which is not overcast,
     * and the sky over the province at the time was mostly blue.
     */
    @Test
    fun `a dry sky is decided by how much cloud the models publish, not by what they call it`() {
        fun saying(source: Source, condition: Condition, cloud: Int) = source to forecast(
            source, listOf(point(0, 20.0, condition = condition).copy(cloudPct = cloud)),
        )
        val f = mapOf(
            saying(Source.ICON_CH1, Condition.CLOUDY, 100),
            saying(Source.ICON_D2, Condition.CLOUDY, 100),
            saying(Source.DMI_HARMONIE, Condition.CLOUDY, 87),
            saying(Source.ICON_CH2, Condition.PARTLY_CLOUDY, 71),
            saying(Source.ICON_2I, Condition.PARTLY_CLOUDY, 61),
            saying(Source.KNMI_HARMONIE, Condition.CLEAR, 14),
        )
        assertEquals(Condition.PARTLY_CLOUDY, blender.blend(f).hourly.single().condition)
        // The labels alone, which is what it used to do, still say overcast.
        assertEquals(
            Condition.CLOUDY,
            ConsensusBlender.voteCondition(f.values.map { it.hourly.single().condition }, precipMm = 0.0),
        )
    }

    /** The boundaries are the WMO codes' own, so a label keeps meaning what the models mean by it. */
    @Test
    fun `the cloud thresholds are the ones the model codes imply`() {
        assertEquals(Condition.CLEAR, ConsensusBlender.fromCloudCover(0.0))
        assertEquals(Condition.CLEAR, ConsensusBlender.fromCloudCover(12.0))
        assertEquals(Condition.MOSTLY_CLEAR, ConsensusBlender.fromCloudCover(13.0))
        assertEquals(Condition.MOSTLY_CLEAR, ConsensusBlender.fromCloudCover(49.0))
        assertEquals(Condition.PARTLY_CLOUDY, ConsensusBlender.fromCloudCover(60.0))
        assertEquals(Condition.CLOUDY, ConsensusBlender.fromCloudCover(95.0))
    }

    /**
     * A median over one or two numbers is one model's opinion wearing a statistic's clothes, so the
     * labels keep their vote. SIAG KMOS publishes no cloud cover at all.
     */
    @Test
    fun `too few sources publishing cloud falls back to the labels`() {
        val conditions = listOf(Condition.CLOUDY, Condition.CLOUDY, Condition.CLEAR)
        assertEquals(
            Condition.CLOUDY,
            ConsensusBlender.voteCondition(conditions, precipMm = 0.0, cloudPct = listOf(5, 5)),
        )
        assertEquals(
            Condition.CLEAR,
            ConsensusBlender.voteCondition(conditions, precipMm = 0.0, cloudPct = listOf(5, 5, 5)),
        )
    }

    /** A wet hour is still decided the way it was: cloud cover says nothing about rain. */
    @Test
    fun `cloud cover does not touch a wet hour`() {
        assertEquals(
            Condition.RAIN,
            ConsensusBlender.voteCondition(
                listOf(Condition.RAIN, Condition.RAIN, Condition.CLOUDY),
                precipMm = 2.0, cloudPct = listOf(0, 0, 0),
            ),
        )
    }

    private fun ensemble(halfWidth: Double, hours: Int = 24) = it.apexweather.data.remote.EnsembleSpread(
        fetchedAt = T0, memberCount = 20,
        halfWidthByEpochSecond = (0 until hours).associate { hour(it).epochSecond to halfWidth },
    )

    /**
     * The models disagreeing by six degrees and an ensemble that is confident are different claims,
     * and the badge should report the second where it exists.
     */
    @Test
    fun `the ensemble's own spread is preferred to the models' disagreement`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 8.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
        )
        val without = blender.blend(f).hourly.single()
        val with = blender.blend(f, ModelBias.NONE, T0, ensemble(halfWidth = 0.5)).hourly.single()

        assertNull(without.ensembleHalfWidthC)
        assertEquals(0.5, with.ensembleHalfWidthC!!, 0.0)
        // Six degrees apart, so the model spread reads as total disagreement; the ensemble does not.
        assertTrue("agreement was ${without.agreement}", without.agreement < 0.1f)
        assertTrue("agreement was ${with.agreement}", with.agreement > 0.7f)
    }

    /**
     * The far end of the day list, and the reason ECMWF's ensemble is fetched at all.
     *
     * Past about day five only ECMWF reaches, so the day carries one model and there is no spread
     * between models to measure. Its own fifty ensemble members are what is left to measure, and
     * the day has to carry that fact out to the badge — otherwise a day with a real, measured
     * confidence is painted grey and labelled "1 model".
     */
    @Test
    fun `a day only one model reaches still carries the ensemble's own spread`() {
        val f = mapOf(Source.ECMWF to forecast(Source.ECMWF, (0 until 48).map { point(it, 10.0) }))
        val day = blender.blend(f, ModelBias.NONE, T0, ensemble(halfWidth = 1.0, hours = 48)).daily.first()
        assertEquals(1, day.sourceCount)
        assertEquals(1.0, day.ensembleHalfWidthC!!, 1e-9)
        // Two degrees of full range out of the six the scale runs over: two thirds agreement.
        assertTrue("agreement was ${day.agreement}", day.agreement > 0.6f)
    }

    /** A day no ensemble reaches says so, rather than reporting a spread of zero. */
    @Test
    fun `a day beyond every ensemble has no half-width at all`() {
        val f = mapOf(Source.ECMWF to forecast(Source.ECMWF, (0 until 48).map { point(it, 10.0) }))
        val days = blender.blend(f, ModelBias.NONE, T0, ensemble(halfWidth = 1.0, hours = 12)).daily
        assertNull(days.last().ensembleHalfWidthC)
    }

    /** An hour the ensemble does not reach falls back to the models, rather than to certainty. */
    @Test
    fun `beyond the ensemble's range the model spread is used again`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 8.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 14.0) }),
        )
        val hourly = blender.blend(f, ModelBias.NONE, T0, ensemble(halfWidth = 0.5, hours = 24)).hourly
        assertEquals(0.5, hourly.first { it.time == hour(5) }.ensembleHalfWidthC!!, 0.0)
        assertNull(hourly.first { it.time == hour(40) }.ensembleHalfWidthC)
        // Six degrees apart. Nearly two days out that is not quite the total disagreement it would
        // be for the current hour, because the scale opens with lead time — see below.
        assertTrue(hourly.first { it.time == hour(40) }.agreement < 0.2f)
    }

    /**
     * The scale the badge is read against opens with lead time, because the spread does.
     *
     * Six degrees between the models an hour from now is them telling you nothing. Six degrees ten
     * days out is an ordinary ten-day forecast — ECMWF's own ensemble opens from about 2 K over the
     * first four days to 8,5 K by day fourteen — and against a fixed scale every far day pins at
     * 0 %, which is a red dot that says the same thing about a settled week as an unsettled one.
     */
    @Test
    fun `the scale for total disagreement opens with lead time`() {
        val hours = 24 * 11
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until hours).map { point(it, 8.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until hours).map { point(it, 14.0) }),
        )
        val hourly = blender.blend(f, now = T0).hourly
        assertEquals(0.0, hourly.first { it.time == hour(0) }.agreement.toDouble(), 1e-6)
        val far = hourly.first { it.time == hour(24 * 10) }.agreement
        assertTrue("ten days out the same six degrees read $far", far > 0.3f)
    }

    /**
     * Taken from the phone on 2026-09-10 at 13:13 local, while it was raining in Dorf Tirol: four
     * models said cloudy, one partly cloudy, two drizzle and one rain, so a plurality vote reported
     * "Bedeckt" — two lines above the app's own "Niederschlag ab 13:15".
     *
     * Being told it is overcast while getting wet is a worse error than being told it drizzles
     * under a grey sky, which is the same asymmetry this blender already applies to gusts and to
     * precipitation probability.
     */
    @Test
    fun `precipitation is not voted away by a plurality of dry models`() {
        val wet = listOf(
            Condition.CLOUDY, Condition.CLOUDY, Condition.CLOUDY, Condition.CLOUDY,
            Condition.PARTLY_CLOUDY, Condition.DRIZZLE, Condition.DRIZZLE, Condition.RAIN,
        )
        val voted = ConsensusBlender.voteCondition(wet, precipMm = 0.4)
        assertTrue("the app reported $voted while it was raining", voted.isPrecipitation)
        // The mildest of the wet ones, not the worst: three of eight is a reason to say it is
        // drizzling, not a reason to promise heavy rain.
        assertEquals(Condition.DRIZZLE, voted)
    }

    /** One model out of ten is an outlier, not a forecast of rain. */
    @Test
    fun `a lone wet model does not turn a dry hour wet`() {
        val mostlyDry = List(9) { Condition.CLOUDY } + Condition.RAIN
        assertEquals(Condition.CLOUDY, ConsensusBlender.voteCondition(mostlyDry, precipMm = 0.3))
    }

    @Test
    fun `a clear majority of wet models still decides on its own`() {
        val soaked = listOf(Condition.RAIN, Condition.RAIN, Condition.RAIN, Condition.CLOUDY)
        assertEquals(Condition.RAIN, ConsensusBlender.voteCondition(soaked, precipMm = 2.0))
    }

    @Test
    fun `an entirely dry hour stays dry`() {
        assertEquals(Condition.CLOUDY, ConsensusBlender.voteCondition(List(8) { Condition.CLOUDY }, precipMm = 0.0))
        assertEquals(Condition.CLEAR, ConsensusBlender.voteCondition(List(4) { Condition.CLEAR }, precipMm = 0.0))
    }

    /**
     * Taken from the phone on 2026-09-10 at 18:10 local. Six models had the 19:00 hour: three dry,
     * and 0,1, 0,2 and 0,5 mm. Half of them wet is enough to win the label, so the strip drew a rain
     * cloud over an empty bar with no millimetres under it, and the hour sheet read
     * "19:00 · Regen · 0,0 mm".
     */
    @Test
    fun `an hour that amounts to nothing is not called rain`() {
        val half = listOf(
            Condition.CLOUDY, Condition.CLOUDY, Condition.CLOUDY,
            Condition.DRIZZLE, Condition.DRIZZLE, Condition.RAIN,
        )
        assertEquals(Condition.CLOUDY, ConsensusBlender.voteCondition(half, precipMm = 0.04))
        // ...and the same labels with something actually falling still read as rain.
        assertTrue(ConsensusBlender.voteCondition(half, precipMm = 0.13).isPrecipitation)
    }

    /**
     * Fog is not precipitation, so the wet-pool rule discarded it outright: once a third of the
     * models forecast drizzle, a source saying Talnebel could not be heard. In a valley where fog
     * and drizzle are the same grey afternoon that loses real fog, and it is why the app had never
     * once shown any.
     */
    @Test
    fun `fog is not voted away by models forecasting drizzle`() {
        val greyAfternoon = listOf(
            Condition.FOG, Condition.FOG, Condition.CLOUDY,
            Condition.DRIZZLE, Condition.DRIZZLE, Condition.CLOUDY,
        )
        assertEquals(Condition.FOG, ConsensusBlender.voteCondition(greyAfternoon, precipMm = 0.3))
    }

    /** Being told it is foggy while a downpour arrives is the same mistake facing the other way. */
    @Test
    fun `real rain outranks fog`() {
        val stormy = listOf(Condition.FOG, Condition.FOG, Condition.RAIN, Condition.RAIN, Condition.RAIN, Condition.CLOUDY)
        assertTrue(ConsensusBlender.voteCondition(stormy, precipMm = 3.0).isPrecipitation)
    }

    /** One source in ten is not a consensus, so on its own it does not carry the hour. */
    @Test
    fun `a lone fog model does not decide the hour`() {
        val mostlyCloudy = List(9) { Condition.CLOUDY } + Condition.FOG
        assertEquals(Condition.CLOUDY, ConsensusBlender.voteCondition(mostlyCloudy, precipMm = 0.0))
    }

    /**
     * ...unless the station is standing in saturated air, which is ground truth against a forecast.
     * It lowers the bar from a third of the sources to one; it never invents fog on its own.
     */
    @Test
    fun `a saturated station lets a single source carry the fog`() {
        val mostlyCloudy = List(9) { Condition.CLOUDY } + Condition.FOG
        assertEquals(Condition.FOG, ConsensusBlender.voteCondition(mostlyCloudy, precipMm = 0.0, stationSaturated = true))
    }

    @Test
    fun `a saturated station does not invent fog nobody forecast`() {
        val allCloudy = List(10) { Condition.CLOUDY }
        assertEquals(Condition.CLOUDY, ConsensusBlender.voteCondition(allCloudy, precipMm = 0.0, stationSaturated = true))
    }

    /** Models that all agree on a trace and nothing else to fall back on keep their own answer. */
    @Test
    fun `with no dry model to fall back on the wet labels stand`() {
        val allWet = List(4) { Condition.DRIZZLE }
        assertEquals(Condition.DRIZZLE, ConsensusBlender.voteCondition(allWet, precipMm = 0.02))
    }

    /**
     * The amount is the **mean**, where every other quantity is the median. Precipitation is
     * zero-inflated: half the models saying dry collapses the median to zero and throws away every
     * wet one, however much rain they forecast.
     */
    @Test
    fun `the hourly amount keeps the wet models when half of them are dry`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 15.0, precip = 0.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 15.0, precip = 0.0, condition = Condition.CLOUDY))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 15.0, precip = 0.0, condition = Condition.CLOUDY))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 15.0, precip = 0.1, condition = Condition.DRIZZLE))),
            Source.KNMI_HARMONIE to forecast(Source.KNMI_HARMONIE, listOf(point(0, 15.0, precip = 0.2, condition = Condition.DRIZZLE))),
            Source.DMI_HARMONIE to forecast(Source.DMI_HARMONIE, listOf(point(0, 15.0, precip = 0.5, condition = Condition.RAIN))),
        )
        val h = blender.blend(f).hourly.single()
        // The median of these six is 0,05 — which prints as "0,0 mm", and is what put a rain cloud
        // over a blank amount. The mean is what the hour actually comes to.
        assertEquals(0.1333, h.precipMm, 1e-3)
        assertTrue("an hour with rain in it should say so", h.condition.isPrecipitation)
    }
}
