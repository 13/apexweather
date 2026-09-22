package it.apexweather.ui.share

import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.SkyPalette
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.home.HomeUiState
import java.time.Instant
import java.time.LocalDate

/**
 * One hour as the shared picture draws it: no click, no per-source table, no agreement badge.
 */
data class ShareHour(
    val time: Instant,
    val tempC: Double,
    val precipMm: Double,
    val snowCm: Double?,
    val condition: Condition,
    val phase: SunPhase,
)

/**
 * Everything the shared picture says, and nothing that would have to be recomputed to draw it.
 *
 * This is deliberately a snapshot rather than a view onto [HomeUiState]: the card is captured into a
 * bitmap, and a bitmap taken while the minute tick moves underneath it would be a picture of two
 * different minutes.
 */
data class ShareCardState(
    val placeName: String,
    val date: LocalDate,
    val tempC: Double?,
    val condition: Condition,
    val phase: SunPhase,
    val minC: Double?,
    val maxC: Double?,
    /** When precipitation begins, where it has not already — the hero's own line, carried across. */
    val precipStart: Instant?,
    val precipProb: Int?,
    /**
     * How far the station's reading had to be moved to stand for the village, or null where the
     * number on the card is not a moved reading.
     *
     * Carried because CLAUDE.md's rule is flat — a moved reading always says so — and an image that
     * drops the caveat is the same number with its provenance cut off, travelling further than the
     * screen it came from.
     */
    val adjustmentC: Double?,
    /** How many models the day was built from, for the footer. Zero where nothing was blended. */
    val sourceCount: Int,
    val palette: SkyPalette,
    val hours: List<ShareHour>,
)

/**
 * Builds [ShareCardState] from the state the home screen already holds.
 *
 * **Nothing here re-derives weather.** The hero on [HomeUiState] has already been through
 * `StationDownscale`, `StationFog`, `StationSun`, `StationDry` and `MeasuredRain`; running any of
 * that again over the same hour would put a second answer about one hour on the reader's screen,
 * which is the failure the hero/strip rule in CLAUDE.md exists to prevent. This file chooses which
 * facts travel and in which order, and that is all it does — so the choosing is testable on the JVM
 * and none of it lives in a composable.
 */
object ShareCardStateBuilder {

    /** Columns on today's strip. Eight fits the card's width at the size the text is drawn. */
    const val TODAY_COLUMNS = 8

    /** A future day is drawn at three-hour steps, from [DAY_FROM_HOUR] to [DAY_TO_HOUR]. */
    const val DAY_FROM_HOUR = 6
    const val DAY_TO_HOUR = 21
    const val DAY_STEP_HOURS = 3

    /**
     * Today's card: the hero as the screen shows it, and the day's own hours around it.
     *
     * Returns null where there is nothing to share. Before the first fetch the home screen is a
     * blank sky, and a card of dashes is worse than no card — the buttons are disabled for exactly
     * this state rather than producing one.
     */
    fun today(state: HomeUiState, locale: java.util.Locale): ShareCardState? {
        val place = state.place ?: return null
        if (state.isEmpty) return null
        val date = state.now.atZone(SouthTyrol.ZONE).toLocalDate()
        val day = state.days.firstOrNull { it.date == date }
        val hours = todayHours(state, date)
        return ShareCardState(
            placeName = place.name(locale),
            date = date,
            tempC = state.heroTempC,
            condition = state.heroCondition,
            phase = state.phase,
            minC = day?.minC,
            maxC = day?.maxC,
            precipStart = state.minutelyStart,
            precipProb = state.currentHour?.precipProb,
            adjustmentC = state.heroAdjustmentC,
            sourceCount = day?.sourceCount ?: state.currentHour?.sourceCount ?: 0,
            palette = state.palette,
            hours = hours,
        )
    }

    /**
     * A day from the day sheet.
     *
     * It carries **no station reading and no adjustment**: a thermometer speaks for the hour it
     * measured, and a measurement has nothing to say about Thursday. The temperature shown is the
     * day's high, which is what a day row leads with.
     */
    fun day(state: HomeUiState, date: LocalDate, locale: java.util.Locale): ShareCardState? {
        val place = state.place ?: return null
        val day = state.days.firstOrNull { it.date == date } ?: return null
        if (date == state.now.atZone(SouthTyrol.ZONE).toLocalDate()) return today(state, locale)
        return ShareCardState(
            placeName = place.name(locale),
            date = date,
            tempC = day.maxC,
            condition = day.condition,
            phase = SunPhase.DAY,
            minC = day.minC,
            maxC = day.maxC,
            precipStart = null,
            precipProb = day.precipProb,
            adjustmentC = null,
            sourceCount = day.sourceCount,
            palette = state.palette,
            hours = dayHours(state, date),
        )
    }

    /**
     * Today's eight columns, and why they are not simply "the next eight".
     *
     * A share at nine in the evening has three hours of today left, and a strip that then ran into
     * tomorrow would be a card headed with today's date describing two days. So it walks forward
     * from the current hour within the day and, where that leaves fewer than [TODAY_COLUMNS], backs
     * up into the hours already gone — the strip is always eight columns wide and always about one
     * day, and late in the evening it reads as a summary of the day rather than of what little is
     * left of it.
     */
    private fun todayHours(state: HomeUiState, date: LocalDate): List<ShareHour> {
        val ofDay = state.hoursByDate[date].orEmpty().ifEmpty {
            state.upcomingHours.filter { it.time.atZone(SouthTyrol.ZONE).toLocalDate() == date }
        }
        if (ofDay.isEmpty()) return emptyList()
        val firstAhead = ofDay.indexOfFirst { !it.time.isBefore(state.now.truncatedToHour()) }
        val start = when {
            firstAhead < 0 -> (ofDay.size - TODAY_COLUMNS).coerceAtLeast(0)
            else -> firstAhead.coerceAtMost((ofDay.size - TODAY_COLUMNS).coerceAtLeast(0))
        }
        return ofDay.drop(start).take(TODAY_COLUMNS).map { it.toShareHour(state) }
    }

    /** A future day at three-hour steps, so six columns cover the daylight a reader plans around. */
    private fun dayHours(state: HomeUiState, date: LocalDate): List<ShareHour> {
        val ofDay = state.hoursByDate[date].orEmpty()
        return ofDay.filter {
            val h = it.time.atZone(SouthTyrol.ZONE).hour
            h in DAY_FROM_HOUR..DAY_TO_HOUR && (h - DAY_FROM_HOUR) % DAY_STEP_HOURS == 0
        }.map { it.toShareHour(state) }
    }

    private fun it.apexweather.domain.model.ConsensusHour.toShareHour(state: HomeUiState) = ShareHour(
        time = time,
        tempC = tempC,
        precipMm = precipMm,
        snowCm = snowCm,
        condition = condition,
        phase = state.phaseAt(time),
    )

    private fun Instant.truncatedToHour(): Instant =
        atZone(SouthTyrol.ZONE).withMinute(0).withSecond(0).withNano(0).toInstant()
}
