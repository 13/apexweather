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
 * How much of the forecast the picture carries.
 *
 * Three, and not a slider: the two questions people actually have are "the next few days" and "the
 * week". A date picker on a share sheet is a different product.
 *
 * Fourteen is deliberately absent even though the day list runs that far. Past about day ten the
 * consensus is down to one model, and the list can afford that because the reader can tap the day
 * and be told what the grey dot means. A picture cannot be tapped, so it would carry a caveat its
 * recipient can do nothing with.
 */
enum class ShareRange(val days: Int) {
    TODAY(0),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
}

/**
 * One day as the shared picture draws it.
 *
 * It carries its **own** agreement and source count rather than the card's, because that is the
 * whole argument for showing a week at all: `ApexWidget`'s tall size stops at five days on the
 * ground that past about day five only the globals reach and a widget has nowhere to put the badge
 * or its explanation. A card has room for both, so the far days say for themselves how well the
 * models agreed.
 */
data class ShareDay(
    val date: LocalDate,
    val condition: Condition,
    val minC: Double,
    val maxC: Double,
    val precipMm: Double,
    val snowCm: Double?,
    val precipProb: Int,
    val agreement: Float,
    val sourceCount: Int,
    /** Whether an ensemble reached this day, which is what makes a one-model day's dot coloured. */
    val ensembleBacked: Boolean,
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
    /** Today's hours — populated for [ShareRange.TODAY] and empty for the day ranges. */
    val hours: List<ShareHour>,
    /** The day rows — populated for the day ranges and empty for [ShareRange.TODAY]. */
    val days: List<ShareDay> = emptyList(),
    val range: ShareRange = ShareRange.TODAY,
) {
    /**
     * Whether any day on this card is down to a single model, which is when the tail sentence has to
     * be said.
     *
     * Read from the data rather than from the arithmetic that it cannot happen inside seven days.
     * It is true today that GFS reaches 336 h, GEM 243 h and UKMO 171 h, so day seven still has
     * several models — but a reach that shortens upstream must not quietly turn this card into one
     * model's opinion wearing a consensus's clothes.
     */
    val hasSingleModelDay: Boolean get() = days.any { it.sourceCount == 1 }

    /** And whether an ensemble stood behind those days, which decides which sentence it is. */
    val singleModelDaysAreEnsembleBacked: Boolean
        get() = days.any { it.sourceCount == 1 && it.ensembleBacked }
}

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
    fun today(
        state: HomeUiState,
        locale: java.util.Locale,
        range: ShareRange = ShareRange.TODAY,
    ): ShareCardState? {
        val place = state.place ?: return null
        if (state.isEmpty) return null
        val date = state.now.atZone(SouthTyrol.ZONE).toLocalDate()
        val day = state.days.firstOrNull { it.date == date }
        // One or the other, never both: "what is this afternoon like" and "what is the week like"
        // are different questions, and a card answering both is two cards stapled together — taller
        // than a chat preview shows, with the important half below the crop.
        val hours = if (range == ShareRange.TODAY) todayHours(state, date) else emptyList()
        val days = if (range == ShareRange.TODAY) emptyList() else dayRows(state, date, range.days)
        return ShareCardState(
            placeName = place.name(locale),
            date = date,
            // The hero, unless the hero is an amateur reading — Weather Underground's data is not
            // licensed for redistribution and this PNG goes into somebody else's chat. Then it is
            // the models' own value for the hour. See HomeUiState.publishableTempC.
            tempC = if (state.observationIsPrivate) state.publishableTempC else state.heroTempC,
            condition = state.heroCondition,
            phase = state.phase,
            minC = day?.minC,
            maxC = day?.maxC,
            precipStart = state.minutelyStart,
            precipProb = state.currentHour?.precipProb,
            // Nothing was adjusted when the number is the models' own, and a card claiming a
            // correction that did not happen is worse than one that says nothing.
            adjustmentC = state.heroAdjustmentC?.takeUnless { state.observationIsPrivate },
            sourceCount = day?.sourceCount ?: state.currentHour?.sourceCount ?: 0,
            palette = state.palette,
            hours = hours,
            days = days,
            range = range,
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

    /**
     * The day rows, from today forward.
     *
     * Whatever the forecast actually reaches, never padded: a six-day card on a short forecast is
     * honest where a seventh empty row is a claim about a day nobody computed.
     */
    private fun dayRows(state: HomeUiState, from: LocalDate, count: Int): List<ShareDay> =
        state.days.filter { !it.date.isBefore(from) }.take(count).map {
            ShareDay(
                date = it.date,
                condition = it.condition,
                minC = it.minC,
                maxC = it.maxC,
                precipMm = it.precipMm,
                snowCm = it.snowCm,
                precipProb = it.precipProb,
                agreement = it.agreement,
                sourceCount = it.sourceCount,
                ensembleBacked = it.ensembleHalfWidthC != null,
            )
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
