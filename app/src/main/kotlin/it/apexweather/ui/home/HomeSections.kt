package it.apexweather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.Warning
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.color
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun HeroSection(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    onOpenPlaces: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.Start) {
        // The place name is already the first line on the screen, so it is the button too rather
        // than adding a second affordance to a screen whose point is the sky behind it.
        //
        // The share icon is the one thing that sits on this line beside it, and it sits at the far
        // *end* of the row rather than next to the name — outside the place button's tap target, so
        // the rule above still holds for the name itself. It is its own button for the reason the
        // pin star is: choosing a place and sharing one are different acts and must not share a
        // gesture. Its test tag goes **inside** clearAndSetSemantics, because that clears the node's
        // whole config and a tag further along the chain is cleared with it — which is why nothing
        // tested the star until it was reported by hand.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable(onClickLabel = stringResource(R.string.change_place), onClick = onOpenPlaces)
                    .padding(vertical = 2.dp)
                    .testTag("place_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    state.place?.name(locale).orEmpty(),
                    style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f),
                )
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Drawn dim rather than hidden while there is nothing to share: "not yet" and "never"
            // are different messages, and the first one resolves itself in a few seconds.
            val canShare = !state.isEmpty && state.place != null
            val shareLabel = stringResource(R.string.share_today)
            IconButton(
                onClick = onShare,
                enabled = canShare,
                modifier = Modifier.size(32.dp).clearAndSetSemantics {
                    contentDescription = shareLabel
                    testTag = "share_today"
                    if (!canShare) disabled()
                },
            ) {
                Icon(
                    Icons.Filled.Share, contentDescription = null,
                    tint = Color.White.copy(alpha = if (canShare) 0.75f else 0.3f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // The number and the picture are the two halves of one answer, so they share a line. The
        // icon carries no content description: the word for the condition is directly underneath,
        // and describing the icon as well would have a screen reader say the weather twice.
        //
        // Its size comes from the temperature's own type rather than a constant, so the two stay in
        // proportion at every font scale. A factor of 0.8 put the icon's ink at exactly the digits'
        // cap height, which read as timid beside a 57 sp number; at 1.05 it stands a little above
        // them, which is what the reader asked for and what the hero can carry.
        val tempStyle = MaterialTheme.typography.displayLarge
        val iconSize = with(LocalDensity.current) { (tempStyle.fontSize * 1.05f).toDp() }
        HeroLine(
            temperature = {
                Text(
                    text = state.heroTempC?.let { Format.temp(it, formats) } ?: "–",
                    style = tempStyle,
                    color = Color.White,
                    modifier = Modifier.testTag("hero_temp"),
                )
            },
            icon = {
                Icon(
                    painterResource(state.heroCondition.iconRes(state.phase)),
                    contentDescription = null,
                    tint = Color.fromArgb(state.palette.accent),
                    modifier = Modifier.size(iconSize).testTag("hero_condition_icon"),
                )
            },
        )
        // The rain line is weather rather than provenance and the one worth reading first, so it
        // shares the condition's line, on the right under the icon, sitting on the word's baseline.
        // Where the two do not fit side by side — a long condition, a large text size — the rain
        // line wraps to a line of its own rather than squeezing the condition.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                state.heroCondition.label(),
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.alignByBaseline(),
            )
            state.minutelyStart?.let {
                Text(
                    stringResource(R.string.rain_starts_at, Format.time(it, SouthTyrol.ZONE, formats)),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.alignByBaseline().padding(start = 12.dp).testTag("rain_starts_at"),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val sourceCount = state.currentHour?.sourceCount ?: 0
        // A moved reading has to say it was moved, and by how much: it is still a measurement, but
        // not one taken where the reader is standing.
        // It no longer names the place it was converted *to*, and it no longer says "Jetzt".
        // Both were true and both were already on the screen: the place is the first line of this
        // block and the number directly above this line is plainly the current one. With them in,
        // the sentence wrapped to two lines at 384 dp and the hero carried three lines of footnote;
        // without them it is one line, and the block is a row shorter for nothing given up.
        val source = state.observation?.let { obs ->
            val at = Format.timestamp(obs.time, SouthTyrol.ZONE, state.now, formats)
            state.heroAdjustmentC
                ?.let { stringResource(R.string.now_from_station_adjusted, obs.stationName, at, Format.tempDelta(it, formats)) }
                ?: stringResource(R.string.now_from_station, obs.stationName, at)
        } ?: pluralStringResource(R.plurals.now_from_consensus, sourceCount, sourceCount)

        // Where the number came from and when it was fetched sit on the left as two plain lines: the
        // hero carries no feels-like reading and no spread badge, so nothing competes with them.
        HeroQuiet(state, formats, source)
    }
}

/**
 * How far the models spread, as a coloured pill. [halfWidth] is half the min/max band in degrees;
 * a day has no single band to quote, so it passes [showSpread] false and shows the dot alone.
 *
 * [ensembleBacked] says an ensemble reached this hour or day. It matters because a single model
 * with an ensemble behind it is not the same thing as a single model alone.
 */
@Composable
fun AgreementBadge(
    halfWidth: Double,
    agreement: Float,
    sourceCount: Int = 0,
    showSpread: Boolean = true,
    ensembleBacked: Boolean = false,
    tag: String = "agreement_badge",
    /**
     * The type inside the pill. Everything else about the badge is derived from it — the dot, the
     * padding and the gap are all fractions of the text size — so a caller that wants a larger badge
     * asks for larger text and the rest follows in proportion. The ratios reproduce the numbers this
     * badge was drawn with at [MaterialTheme.typography.labelSmall], so the three callers that do
     * not pass one are unchanged to the pixel.
     */
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    // One model with nothing behind it has nothing to agree with, and saying "50 %" there would
    // invent a comparison that never happened. One model whose own fifty ensemble members have been
    // counted is a different case: the percentage is measured, not inferred, and is the only thing
    // the far end of the day list has to offer.
    val single = sourceCount == 1 && !ensembleBacked
    val color = if (single) SingleModelColor else agreementColor(agreement)
    val description = if (single) stringResource(R.string.agreement_single)
    else stringResource(R.string.agreement_desc, (agreement * 100).roundToInt())
    val density = LocalDensity.current
    fun ofText(fraction: Float) = with(density) { (textStyle.fontSize * fraction).toDp() }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f))
            .padding(horizontal = ofText(BadgePadHToText), vertical = ofText(BadgePadVToText))
            .semantics { contentDescription = description }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ofText(BadgeGapToText)),
    ) {
        Box(Modifier.size(ofText(BadgeDotToText)).clip(CircleShape).background(color))
        when {
            single -> Text(stringResource(R.string.agreement_single), style = textStyle, color = Color.White)
            showSpread -> Text("±${halfWidth.roundToInt()}°", style = textStyle, color = Color.White)
            else -> Text(stringResource(R.string.agreement_short, (agreement * 100).roundToInt()), style = textStyle, color = Color.White)
        }
    }
}

// The badge's proportions, as fractions of its own text size. They are the numbers it was drawn
// with — 7, 10, 3 and 6 dp against an 11 sp label — expressed as ratios so the whole pill grows with
// its type instead of a large badge being a small one with big letters in it.
private const val BadgeDotToText = 7f / 11f
private const val BadgePadHToText = 10f / 11f
private const val BadgePadVToText = 3f / 11f
private const val BadgeGapToText = 6f / 11f

/**
 * How wide one hour of the strip is at the system's normal text size.
 *
 * Forty-six rather than fifty-eight. The widest thing a column ever holds is its millimetre label —
 * "0,2 mm" is about 32 dp at 10 sp — so 46 leaves a real gutter and fits **seven** columns in the
 * 320 dp the card has, against five and a half before. Measured on the phone at 384 x 832 dp.
 *
 * It is a *base*, and [hourColumnWidth] widens it with the reader's text size, which the flat
 * constant never did: at a 2x font scale that same label is already wider than fifty-eight, so the
 * old number was not a safe width either — it was an unsafe one that happened to be larger.
 */
private val HourColumnBaseWidth = 46.dp

/** [HourColumnBaseWidth] in step with the reader's text size, never narrower than the base. */
@Composable
private fun hourColumnWidth(): Dp = HourColumnBaseWidth * maxOf(1f, LocalDensity.current.fontScale)

@Composable
fun HourlySection(hours: List<ConsensusHour>, phaseAt: (Instant) -> SunPhase, onHourClick: (Instant) -> Unit) {
    if (hours.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.section_hourly), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        // Without this the bar is a shape with no stated meaning, which is how it came to be read
        // as the amount when it is the chance.
        Text(
            stringResource(R.string.precip_legend),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.testTag("precip_legend"),
        )
        Spacer(Modifier.height(8.dp))
        HourStrip(hours, phaseAt, tagPrefix = "hour_column", onHourClick = onHourClick, modifier = Modifier.testTag("hourly_strip"))
    }
}

/**
 * The scrolling hour columns under their temperature curve, without a card around them, so both the
 * home screen section and the day sheet can show the same strip. [tagPrefix] keeps the two uses'
 * test tags apart. A null [onHourClick] leaves the columns inert — the day sheet does not open a
 * second sheet on top of itself.
 */
@Composable
fun HourStrip(
    hours: List<ConsensusHour>,
    phaseAt: (Instant) -> SunPhase,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    onHourClick: ((Instant) -> Unit)? = null,
    labelFirstAsNow: Boolean = true,
) {
    if (hours.isEmpty()) return
    val scroll = rememberScrollState()
    val formats = LocalFormats.current
    val columnWidth = hourColumnWidth()
    Column(modifier.horizontalScroll(scroll)) {
        val openLabel = stringResource(R.string.open_hour_details)
        Row {
            hours.forEachIndexed { i, h ->
                // clickable is what merges a column into one spoken node, so the inert strip has to
                // say so itself — otherwise every hour is read as four unrelated fragments.
                val spoken = stringResource(
                    R.string.hour_column_desc, Format.hour(h.time, SouthTyrol.ZONE, formats),
                    h.condition.label(), Format.temp(h.tempC, formats), h.precipProb,
                    // Whatever the column prints, so the spoken hour and the drawn one cannot
                    // disagree about whether it is eight centimetres or eight tenths of a millimetre.
                    Format.precip(h.precipMm, h.snowCm, h.condition, formats),
                )
                val behaviour = if (onHourClick == null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
                else Modifier.clickable(onClickLabel = openLabel) { onHourClick(h.time) }
                Column(
                    Modifier.width(columnWidth).then(behaviour)
                        .padding(vertical = 6.dp).testTag("${tagPrefix}_$i"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (i == 0 && labelFirstAsNow) stringResource(R.string.now) else Format.hour(h.time, SouthTyrol.ZONE, formats),
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Icon(painterResource(h.condition.iconRes(phaseAt(h.time))), contentDescription = h.condition.label(), tint = Color.White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(Format.temp(h.tempC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    PrecipBar(h.precipMm, h.snowCm, h.precipProb, h.condition)
                }
            }
        }
    }
}

/**
 * Millimetres as a bar, the same millimetres as a number under it, and the probability under that.
 * See [PrecipScale] for the scale and why it is not linear.
 */
@Composable
private fun PrecipBar(mm: Double, snowCm: Double?, prob: Int, condition: Condition) {
    val formats = LocalFormats.current
    // A frozen hour is drawn and printed in centimetres of snow, not in the millimetres of water it
    // would melt down to. Both scales fill the same track at roughly the same weather — see
    // PrecipScale.FULL_SCALE_CM — so a full bar goes on meaning the same thing in either season.
    val snow = PrecipScale.showsSnow(snowCm, condition)
    val fraction = if (snow) PrecipScale.snowFillFraction(snowCm!!) else PrecipScale.fillFraction(mm)
    val amount = when {
        snow -> Format.precip(mm, snowCm, condition, formats)
        PrecipScale.hasAmount(mm) -> Format.mm(mm, formats)
        else -> ""
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(18.dp).height(PrecipTrackHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(PrecipScale.TRACK),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (snow || PrecipScale.hasAmount(mm)) {
                Box(
                    Modifier.fillMaxWidth()
                        // The floor keeps the smallest printed amount from rounding away to nothing.
                        .height((PrecipTrackHeight * fraction).coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(PrecipScale.fillColor(mm, condition)),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            amount,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (prob > 0) stringResource(R.string.unit_percent, prob) else "",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color(0xFFB9D2F5),
        )
    }
}

/** The track every hour's bar is drawn inside, so a small amount reads as small, not as absent. */
private val PrecipTrackHeight = 30.dp

@Composable
fun DailySection(days: List<ConsensusDay>, accent: Color, onDayClick: (LocalDate) -> Unit) {
    if (days.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    val globalMin = days.minOf { it.minC }
    val globalMax = days.maxOf { it.maxC }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("daily_list")) {
        Text(stringResource(R.string.section_daily_long), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        val openLabel = stringResource(R.string.open_day_details)
        days.forEachIndexed { i, d ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { onDayClick(d.date) }
                    .heightIn(min = DayRowMinHeight)
                    .padding(vertical = 6.dp)
                    .testTag("day_row_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (i == 0) stringResource(R.string.today) else Format.weekday(d.date, formats),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.width(52.dp),
                )
                Icon(painterResource(d.condition.iconRes(SunPhase.DAY)), contentDescription = d.condition.label(), tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                // Amount over chance, the same two facts in the same order the hour columns put
                // them in. The chance is what the row was missing: it had "how much" and left "will
                // it rain on Saturday" to be guessed off the icon. See ConsensusDay.precipProb.
                Column(Modifier.width(48.dp)) {
                    val frozen = Format.showsSnow(d.snowCm, d.condition)
                    Text(
                        when {
                            frozen -> Format.precip(d.precipMm, d.snowCm, d.condition, formats)
                            d.precipMm >= 0.5 -> Format.mm(d.precipMm, formats)
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5),
                    )
                    Text(
                        if (d.precipProb > 0) stringResource(R.string.unit_percent, d.precipProb) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB9D2F5).copy(alpha = 0.7f),
                        modifier = Modifier.testTag("day_precip_prob_$i"),
                    )
                }
                Text(Format.temp(d.minC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(36.dp))
                RangeBar(d.minC, d.maxC, globalMin, globalMax, accent, Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(Format.temp(d.maxC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
                AgreementDot(d.agreement, d.sourceCount, ensembleBacked = d.ensembleHalfWidthC != null)
            }
        }
        // Only worth saying once, and only when the list actually reaches that far. Which of the two
        // sentences it is depends on whether ECMWF's ensemble reached those days: a coloured dot on
        // a single-model day has to be accounted for, and a grey one has to be explained.
        val tail = days.filter { it.sourceCount == 1 }
        if (tail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (tail.any { it.ensembleHalfWidthC != null }) R.string.daily_tail_note_ensemble
                    else R.string.daily_tail_note,
                ),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.testTag("daily_tail_note"),
            )
        }
    }
}

@Composable
private fun RangeBar(min: Double, max: Double, gMin: Double, gMax: Double, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (gMax - gMin).coerceAtLeast(1.0)
        val x0 = ((min - gMin) / span * size.width).toFloat()
        val x1 = ((max - gMin) / span * size.width).toFloat()
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF8FB3E8), accent), startX = x0, endX = x1),
            topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

/** Green when the models agree, amber when they drift apart, red when they disagree outright. */
internal fun agreementColor(agreement: Float): Color = when {
    agreement >= 0.75f -> Color(0xFF7CE0A5)
    agreement >= 0.45f -> Color(0xFFFFD166)
    else -> Color(0xFFFF8A80)
}

/** Neutral grey: not agreement, not disagreement, simply nothing to compare. */
private val SingleModelColor = Color(0xFF9AA6B8)

@Composable
private fun AgreementDot(agreement: Float, sourceCount: Int, ensembleBacked: Boolean) {
    val single = sourceCount == 1 && !ensembleBacked
    val color = if (single) SingleModelColor else agreementColor(agreement)
    val description = if (single) stringResource(R.string.agreement_single)
    else stringResource(R.string.agreement_desc, (agreement * 100).roundToInt())
    Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(color).semantics { contentDescription = description })
}

@Composable
fun BulletinTeaser(bulletin: Bulletin, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick).testTag("bulletin_teaser")) {
        Text(stringResource(R.string.section_bulletin), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        Text(bulletin.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(bulletin.evolution.substringBefore('\n').take(180), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 3)
    }
}

@Composable
fun AttributionFooter() {
    Text(
        stringResource(R.string.attribution),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/**
 * How tall a row of the day list is, and the one measurement in this app deliberately under a
 * guideline.
 *
 * Android's minimum touch target is 48 dp and the rows open the day sheet, so 48 is what they were:
 * measured on the phone, the pitch was exactly 48,0 dp, held there by this minimum over content
 * only 16,7 dp tall. Forty-four is a choice to sit 4 dp under that, taken deliberately and worth
 * 56 dp over the fourteen rows — more than a whole extra row on screen.
 *
 * What makes it defensible rather than merely smaller: the row is the **full width of the card**, so
 * the target is 44 dp by about 320, and a miss is only possible vertically between two rows that
 * both do something harmless — open the day above or the day below. It is not a 44 dp square, and it
 * is not next to anything destructive.
 *
 * It cannot go much further. The tallest thing in the row is the stacked amount-over-chance column
 * at about 26 dp, and with 6 dp of padding either side the content itself wants 38; below about
 * 42 the rows stop having any air in them at all and the minimum stops doing anything.
 */
private val DayRowMinHeight = 44.dp

/**
 * The hero's number and its picture: the number at the margin, the icon **centred in the space that
 * is left between the number and the far edge**.
 *
 * It was a `Row` of two equally weighted children, which is not that and only looks like it. A
 * weighted child's slot is a fixed half of the line, but children are *placed* one after another at
 * their measured widths — so the icon's box began wherever the number happened to end and then
 * extended a full half-width past it, and the icon was centred in that. Measured off the phone: the
 * icon's ink centre sat at 233 dp where the space after the number runs 142 to 360 and its middle is
 * 251. It drifted with the temperature, which is exactly what the old comment claimed it would not
 * do.
 *
 * Centring it on the *card* was tried first and is a different request: it put the icon at 199 dp,
 * true to the middle of the line but leaving the right third of the hero empty and the icon crowded
 * against the number.
 *
 * A [Layout] rather than a chain of modifiers because the rule needs both measured widths at once,
 * and writing it out is shorter than the arrangement that would fake it.
 */
/**
 * How far right of that centre the icon sits: centred exactly, it read as a little too close to the
 * number, so it was asked to move slightly right. Still capped at the far edge.
 */
private val HeroIconNudge = 16.dp

@Composable
private fun HeroLine(temperature: @Composable () -> Unit, icon: @Composable () -> Unit) {
    Layout(contents = listOf(temperature, icon)) { (temperatureMeasurables, iconMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0)
        val temp = temperatureMeasurables.first().measure(loose)
        val wx = iconMeasurables.first().measure(loose)
        val width = constraints.maxWidth
        val height = maxOf(temp.height, wx.height)
        // Centred in what is left after the number, which is the whole rule. Where there is less
        // room left than the icon needs — a wide reading at a large font scale — it sits at the far
        // edge and stops, rather than being pushed off it.
        val remaining = (width - temp.width).coerceAtLeast(0)
        val x = (temp.width + (remaining - wx.width) / 2 + HeroIconNudge.roundToPx()).coerceIn(temp.width, (width - wx.width).coerceAtLeast(0))
        layout(width, height) {
            temp.place(0, (height - temp.height) / 2)
            wx.place(x, (height - wx.height) / 2)
        }
    }
}

/**
 * Where the number came from, when it was fetched, and which sources never answered — on the left,
 * one line under another.
 */
@Composable
private fun HeroQuiet(
    state: HomeUiState,
    formats: it.apexweather.ui.common.Formats,
    source: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            source,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.testTag("hero_source"),
        )
        state.updatedAt?.let {
            // Still one line at either reading: the hero's vertical budget was measured, and the
            // footnote was cut from three lines to two to fit. Amber is chrome here, as it is on
            // the rain ribbon, and never a rain colour.
            val stamp = Format.timestamp(it, SouthTyrol.ZONE, state.now, formats)
            Text(
                if (state.staleOnScreen) stringResource(R.string.updated_at_stale, stamp)
                else stringResource(R.string.updated_at, stamp),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.staleOnScreen) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.testTag("hero_updated"),
            )
        }
        // A source that never answers is otherwise invisible: the consensus simply has one model
        // fewer and says nothing about it. Named where there is one, counted where there are more,
        // and in the same quiet type as the rest of this column — it is a fact about the forecast,
        // not an alarm.
        if (state.silentSources.isNotEmpty()) {
            val text = if (state.silentSources.size == 1) {
                stringResource(R.string.source_silent_one, state.silentSources.single().displayName)
            } else {
                pluralStringResource(R.plurals.source_silent_many, state.silentSources.size, state.silentSources.size)
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFD166).copy(alpha = 0.85f),
                modifier = Modifier.testTag("silent_sources"),
            )
        }
    }
}
