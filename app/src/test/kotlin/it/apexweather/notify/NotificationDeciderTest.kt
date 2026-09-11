package it.apexweather.notify

import it.apexweather.data.AppSettings
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import it.apexweather.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The rules behind the three notifications. This is the one part of the app whose behaviour cannot
 * be checked by looking at the screen, so every condition it fires on is pinned here.
 */
class NotificationDeciderTest {

    private val zone = SouthTyrol.ZONE

    /** 07:30 in Rome, i.e. half an hour after the default summary hour. */
    private val morning: Instant = Instant.parse("2026-09-09T05:30:00Z")
    private val today: LocalDate = LocalDate.of(2026, 9, 9)

    private fun day(condition: Condition = Condition.RAIN) = ConsensusDay(
        date = today, minC = 11.0, maxC = 21.0, precipMm = 4.0, condition = condition,
        agreement = 0.8f, sourceCount = 5, freezingLevelMinM = 3100.0, sunrise = null, sunset = null,
    )

    private fun consensusHour(at: Instant, mm: Double, prob: Int, condition: Condition = Condition.RAIN) = ConsensusHour(
        time = at, tempC = 15.0, tempMinC = 14.0, tempMaxC = 16.0, feelsLikeC = null,
        precipMm = mm, precipProb = prob, windKmh = 8.0, gustKmh = 20.0, freezingLevelM = 3000.0,
        condition = condition, agreement = 0.8f, sourceCount = 5, perSource = emptyMap(),
    )

    private fun warning(id: String, level: WarningLevel = WarningLevel.ORANGE) = Warning(
        identifier = id, type = WarningType.THUNDERSTORM, level = level, areaDesc = "Trentino Alto Adige",
        onset = morning, expires = morning.plusSeconds(6 * 3600), headline = "Orange Thunderstorm Warning",
    )

    private fun state(
        days: List<ConsensusDay> = listOf(day()),
        current: ConsensusHour? = consensusHour(morning, mm = 0.0, prob = 5, condition = Condition.CLOUDY),
        upcoming: List<ConsensusHour> = emptyList(),
        warnings: List<Warning> = emptyList(),
    ) = HomeUiState(
        place = it.apexweather.domain.DORF_TIROL,
        days = days, currentHour = current, upcomingHours = upcoming, warnings = warnings,
    )

    private val allOn = AppSettings(notifySummary = true, notifyRain = true, notifyWarnings = true)

    // ---- morning summary ----

    @Test
    fun `the summary goes out once the chosen hour has come`() {
        val out = NotificationDecider.decide(state(), allOn, NotifyMemory(), morning, zone, "Dorf Tirol")
        val summary = out.filterIsInstance<WeatherNotification.Summary>().single()
        assertEquals(today, summary.date)
        // The reader may have switched place since the last summary; the title has to say which.
        assertEquals("Dorf Tirol", summary.placeName)
        assertEquals(21.0, summary.maxC, 0.001)
    }

    @Test
    fun `nothing before the chosen hour`() {
        val sixThirty = Instant.parse("2026-09-09T04:30:00Z")
        val out = NotificationDecider.decide(state(), allOn, NotifyMemory(), sixThirty, zone, "Dorf Tirol")
        assertTrue(out.none { it is WeatherNotification.Summary })
    }

    /** A phone that was off all morning must not be told at six in the evening what the day held. */
    @Test
    fun `a summary hours late is not worth posting`() {
        val evening = Instant.parse("2026-09-09T16:00:00Z")
        val out = NotificationDecider.decide(state(), allOn, NotifyMemory(), evening, zone, "Dorf Tirol")
        assertTrue(out.none { it is WeatherNotification.Summary })
    }

    @Test
    fun `the same day is never summarised twice`() {
        val memory = NotifyMemory(lastSummaryDate = today)
        val out = NotificationDecider.decide(state(), allOn, memory, morning, zone, "Dorf Tirol")
        assertTrue(out.none { it is WeatherNotification.Summary })
    }

    @Test
    fun `the switch being off means silence`() {
        val out = NotificationDecider.decide(state(warnings = listOf(warning("a"))), AppSettings(), NotifyMemory(), morning, zone, "Dorf Tirol")
        assertTrue(out.isEmpty())
    }

    // ---- rain starting ----

    @Test
    fun `rain within the next three hours is announced`() {
        val onset = morning.plusSeconds(2 * 3600)
        val out = NotificationDecider.decide(
            state(upcoming = listOf(consensusHour(onset, mm = 1.4, prob = 70))),
            allOn, NotifyMemory(), morning, zone, "Dorf Tirol",
        )
        assertEquals(onset, out.filterIsInstance<WeatherNotification.RainStarting>().single().hour.time)
    }

    @Test
    fun `rain beyond the lookahead is a forecast, not a heads-up`() {
        val out = NotificationDecider.decide(
            state(upcoming = listOf(consensusHour(morning.plusSeconds(5 * 3600), mm = 1.4, prob = 70))),
            allOn, NotifyMemory(), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.none { it is WeatherNotification.RainStarting })
    }

    @Test
    fun `a shower nobody is sure about stays quiet`() {
        val out = NotificationDecider.decide(
            state(upcoming = listOf(consensusHour(morning.plusSeconds(3600), mm = 1.4, prob = 20))),
            allOn, NotifyMemory(), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.none { it is WeatherNotification.RainStarting })
    }

    /**
     * The threshold is read against the **mean** probability across the models now, not the maximum
     * it was picked for. A third of ten models calling it likely is the same evidence the blender
     * uses to call an hour wet at all, and it is a heads-up rather than a promise.
     */
    @Test
    fun `a third of the models seeing the shower is enough`() {
        val out = NotificationDecider.decide(
            state(upcoming = listOf(consensusHour(morning.plusSeconds(3600), mm = 1.4, prob = 30))),
            allOn, NotifyMemory(), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.any { it is WeatherNotification.RainStarting })
    }

    /** It is already raining: the reader can see that out of the window. */
    @Test
    fun `no announcement while it is already raining`() {
        val out = NotificationDecider.decide(
            state(
                current = consensusHour(morning, mm = 1.0, prob = 90),
                upcoming = listOf(consensusHour(morning.plusSeconds(3600), mm = 1.4, prob = 70)),
            ),
            allOn, NotifyMemory(), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.none { it is WeatherNotification.RainStarting })
    }

    @Test
    fun `the same onset is only announced once`() {
        val onset = morning.plusSeconds(2 * 3600)
        val out = NotificationDecider.decide(
            state(upcoming = listOf(consensusHour(onset, mm = 1.4, prob = 70))),
            allOn, NotifyMemory(lastRainOnset = onset), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.none { it is WeatherNotification.RainStarting })
    }

    // ---- warnings ----

    @Test
    fun `every warning not yet announced goes out`() {
        val out = NotificationDecider.decide(
            state(warnings = listOf(warning("a", WarningLevel.RED), warning("b"))),
            allOn, NotifyMemory(notifiedWarningKeys = setOf("b|ORANGE")), morning, zone, "Dorf Tirol",
        )
        val severe = out.filterIsInstance<WeatherNotification.Severe>()
        assertEquals(listOf("a"), severe.map { it.warning.identifier })
    }

    /**
     * The one case keying on the identifier alone got wrong. A warning that is upgraded in place is
     * the thing the reader most needs to hear about, and it was the only thing that could never be
     * announced: the yellow one had already spoken for the id.
     */
    @Test
    fun `a warning upgraded under the same identifier is announced again`() {
        val out = NotificationDecider.decide(
            state(warnings = listOf(warning("a", WarningLevel.RED))),
            allOn, NotifyMemory(notifiedWarningKeys = setOf("a|YELLOW")), morning, zone, "Dorf Tirol",
        )
        val severe = out.filterIsInstance<WeatherNotification.Severe>()
        assertEquals(listOf(WarningLevel.RED), severe.map { it.warning.level })
    }

    /** And the same one, at the same level, still only goes out once. */
    @Test
    fun `a warning already announced at this level stays quiet`() {
        val out = NotificationDecider.decide(
            state(warnings = listOf(warning("a", WarningLevel.RED))),
            allOn, NotifyMemory(notifiedWarningKeys = setOf("a|RED")), morning, zone, "Dorf Tirol",
        )
        assertTrue(out.none { it is WeatherNotification.Severe })
    }

    /** Ids of warnings that are over are forgotten, so the set cannot grow without bound. */
    @Test
    fun `expired warnings drop out of the memory`() {
        val posted = listOf(WeatherNotification.Severe(warning("new")))
        val memory = NotifyMemory(notifiedWarningKeys = setOf("old|ORANGE", "still-running|ORANGE"))
        val next = NotificationDecider.remember(memory, posted, activeWarningKeys = setOf("new|ORANGE", "still-running|ORANGE"))
        assertEquals(setOf("new|ORANGE", "still-running|ORANGE"), next.notifiedWarningKeys)
    }

    @Test
    fun `what was posted is what is remembered`() {
        val onset = morning.plusSeconds(3600)
        val posted = listOf(
            WeatherNotification.Summary("Dorf Tirol", today, Condition.RAIN, 11.0, 21.0, 4.0),
            WeatherNotification.RainStarting(consensusHour(onset, mm = 1.4, prob = 70), onset),
        )
        val next = NotificationDecider.remember(NotifyMemory(), posted, activeWarningKeys = emptySet())
        assertEquals(today, next.lastSummaryDate)
        assertEquals(onset, next.lastRainOnset)
    }
}
