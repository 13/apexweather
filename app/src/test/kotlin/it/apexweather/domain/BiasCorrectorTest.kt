package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A weather station is the only ground truth this app has, and this is what it is for: over a few
 * days the difference between what a model said and what the thermometer read stops being noise and
 * starts being that model's habit in this valley.
 *
 * Every guard here exists because a correction applied on thin evidence does harm.
 */
class BiasCorrectorTest {

    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun samples(count: Int, error: Double, source: Source = Source.ICON_D2, observed: Double = 10.0) =
        (1..count).map { i ->
            StationSample(now.minusSeconds(i * 3600L), observed, mapOf(source to observed + error))
        }

    @Test
    fun `a model that runs consistently warm is measured as warm`() {
        val bias = BiasCorrector.biases(samples(12, error = 1.5), now)
        assertEquals(1.5, bias.getValue(Source.ICON_D2), 1e-9)
    }

    @Test
    fun `a model that runs cold comes out negative`() {
        assertEquals(-0.8, BiasCorrector.biases(samples(12, error = -0.8), now).getValue(Source.ICON_D2), 1e-9)
    }

    /** Two hours of agreement is luck, not a habit. */
    @Test
    fun `too few hours means no opinion at all`() {
        val bias = BiasCorrector.biases(samples(BiasCorrector.MIN_SAMPLES - 1, error = 2.0), now)
        assertNull("a handful of hours must not produce a correction", bias[Source.ICON_D2])
    }

    @Test
    fun `exactly enough hours is enough`() {
        assertTrue(Source.ICON_D2 in BiasCorrector.biases(samples(BiasCorrector.MIN_SAMPLES, error = 2.0), now))
    }

    /** Beyond a few degrees the input is broken rather than biased, and moving the forecast a long
     * way on bad evidence is worse than leaving it alone. */
    @Test
    fun `an implausible error is refused rather than applied`() {
        assertNull(BiasCorrector.biases(samples(12, error = 9.0), now)[Source.ICON_D2])
    }

    @Test
    fun `hours outside the window are not counted`() {
        val old = (1..12).map { i ->
            StationSample(now.minusSeconds(BiasCorrector.WINDOW.seconds + i * 3600L), 10.0, mapOf(Source.ICON_D2 to 12.0))
        }
        assertNull(BiasCorrector.biases(old, now)[Source.ICON_D2])
    }

    /** Each model is judged on its own record; one absent from an hour is not scored for it. */
    @Test
    fun `models are measured separately`() {
        val mixed = (1..12).map { i ->
            StationSample(
                now.minusSeconds(i * 3600L), 10.0,
                mapOf(Source.ICON_D2 to 11.0, Source.ECMWF to 8.0),
            )
        }
        val bias = BiasCorrector.biases(mixed, now)
        assertEquals(1.0, bias.getValue(Source.ICON_D2), 1e-9)
        assertEquals(-2.0, bias.getValue(Source.ECMWF), 1e-9)
        assertNull(bias[Source.SIAG_KMOS])
    }

    /** A model's habit today is decent evidence about this afternoon and almost none about Thursday. */
    @Test
    fun `the correction fades with lead time`() {
        assertEquals(2.0, BiasCorrector.correctionAt(2.0, leadHours = 0), 1e-9)
        assertEquals(2.0, BiasCorrector.correctionAt(2.0, leadHours = BiasCorrector.FULL_STRENGTH_HOURS), 1e-9)
        assertEquals(0.0, BiasCorrector.correctionAt(2.0, leadHours = BiasCorrector.NO_STRENGTH_HOURS), 1e-9)
        assertEquals(0.0, BiasCorrector.correctionAt(2.0, leadHours = 48), 1e-9)

        val middle = BiasCorrector.correctionAt(2.0, leadHours = 7)
        assertTrue("$middle should sit between", middle > 0.0 && middle < 2.0)
    }

    @Test
    fun `no measured bias means no correction, so a caller may always subtract`() {
        assertEquals(0.0, BiasCorrector.correctionAt(null, leadHours = 1), 0.0)
    }
}
