package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyAggregatorTest {
    @Test
    fun `aggregates min max precip and daytime-worst condition per local day`() {
        // 2026-09-08T00:00Z is 02:00 local. Hours 0..23 UTC cover local 02:00 .. 01:00 next day.
        val hours = (0 until 24).map { i ->
            point(i, temp = 10.0 + i, precip = 0.5,
                condition = if (i == 22) Condition.THUNDERSTORM else Condition.CLEAR) // i=22 → 00:00 local next day
        }
        val days = DailyAggregator.aggregate(hours, ROME)
        assertEquals(2, days.size)
        val d0 = days[0]
        assertEquals(LocalDate.of(2026, 9, 8), d0.date)
        assertEquals(10.0, d0.minC, 0.0)
        assertEquals(31.0, d0.maxC, 0.0) // i=21 → 23:00 local
        assertEquals(11.0, d0.precipMm, 1e-9)
        assertEquals(Condition.CLEAR, d0.condition) // thunderstorm is on the next local day
        assertEquals(Condition.THUNDERSTORM, days[1].condition) // outside 06-22 but only hours → worst of all
    }

    @Test
    fun `daytime window wins over night hours when both exist`() {
        val hours = (0 until 24).map { i ->
            // local hour = i + 2; night hour 03:00 local (i=1) stormy, day hours rain at 12:00 local (i=10)
            val c = when (i) { 1 -> Condition.THUNDERSTORM; 10 -> Condition.RAIN; else -> Condition.CLEAR }
            point(i, temp = 15.0, condition = c)
        }
        val day = DailyAggregator.aggregate(hours, ROME).first { it.date == LocalDate.of(2026, 9, 8) }
        assertEquals(Condition.RAIN, day.condition)
    }

    @Test
    fun `a dry day takes its median sky, not its cloudiest hour`() {
        // Dorf Tirol, Saturday 2026-09-26 as the phone showed it on the 24th: sun from 06 to 18,
        // cloud from 19 to 21, after a 19:05 sunset. The worst hour made the day "Bedeckt".
        val hours = (6 until 22).map { h ->
            h to if (h >= 19) Condition.CLOUDY else Condition.CLEAR
        }
        assertEquals(Condition.CLEAR, DailyAggregator.worstCondition(hours))
    }

    @Test
    fun `a dry day that is mostly overcast stays overcast`() {
        val hours = (6 until 22).map { h ->
            h to if (h < 9) Condition.PARTLY_CLOUDY else Condition.CLOUDY
        }
        assertEquals(Condition.CLOUDY, DailyAggregator.worstCondition(hours))
    }

    @Test
    fun `one wet hour still decides a dry day`() {
        val hours = (6 until 22).map { h ->
            h to if (h == 15) Condition.RAIN else Condition.CLEAR
        }
        assertEquals(Condition.RAIN, DailyAggregator.worstCondition(hours))
    }
}
