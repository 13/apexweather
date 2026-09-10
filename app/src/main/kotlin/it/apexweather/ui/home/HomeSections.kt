package it.apexweather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
fun HeroSection(state: HomeUiState, modifier: Modifier = Modifier, onOpenPlaces: () -> Unit = {}) {
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.Start) {
        // The place name is already the first line on the screen, so it is the button too rather
        // than adding a second affordance to a screen whose point is the sky behind it.
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
        Text(
            text = state.heroTempC?.let { Format.temp(it, formats) } ?: "–",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.testTag("hero_temp"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(state.heroCondition.iconRes(state.phase)), contentDescription = null, tint = Color.fromArgb(state.palette.accent), modifier = Modifier.size(22.dp))
            Text(state.heroCondition.label(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.heroFeelsLikeC?.let { Text(stringResource(R.string.feels_like, Format.temp(it, formats)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)) }
            state.bandHalfWidth?.let { AgreementBadge(it, state.currentHour?.agreement ?: 0.5f, sourceCount = state.currentHour?.sourceCount ?: 0) }
        }
        // A quarter-hour, not an hour: this is the one line on the screen that the sub-hourly series
        // makes honest, and "ab 14:15" is worth more than "ab 14:00" to someone deciding to leave.
        state.minutelyStart?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.rain_starts_at, Format.time(it, SouthTyrol.ZONE, formats)),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.testTag("rain_starts_at"),
            )
        }
        Spacer(Modifier.height(4.dp))
        val sourceCount = state.currentHour?.sourceCount ?: 0
        // A moved reading has to say it was moved, and by how much: it is still a measurement, but
        // not one taken where the reader is standing.
        val source = state.observation?.let { obs ->
            val at = Format.timestamp(obs.time, SouthTyrol.ZONE, state.now, formats)
            state.heroAdjustmentC
                ?.let { stringResource(R.string.now_from_station_adjusted, obs.stationName, at, Format.tempDelta(it, formats), state.place?.name(locale).orEmpty()) }
                ?: stringResource(R.string.now_from_station, obs.stationName, at)
        } ?: pluralStringResource(R.plurals.now_from_consensus, sourceCount, sourceCount)
        Text(source, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
        state.updatedAt?.let {
            Text(stringResource(R.string.updated_at, Format.timestamp(it, SouthTyrol.ZONE, state.now, formats)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

/**
 * How far the models spread, as a coloured pill. [halfWidth] is half the min/max band in degrees;
 * a day has no single band to quote, so it passes [showSpread] false and shows the dot alone.
 */
@Composable
fun AgreementBadge(halfWidth: Double, agreement: Float, sourceCount: Int = 0, showSpread: Boolean = true, tag: String = "agreement_badge") {
    // One model has nothing to agree with. Saying "50 %" there would invent a comparison that never
    // happened, which is exactly what the later days of the week are: ECMWF on its own.
    val single = sourceCount == 1
    val color = if (single) SingleModelColor else agreementColor(agreement)
    val description = if (single) stringResource(R.string.agreement_single)
    else stringResource(R.string.agreement_desc, (agreement * 100).roundToInt())
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { contentDescription = description }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        when {
            single -> Text(stringResource(R.string.agreement_single), style = MaterialTheme.typography.labelSmall, color = Color.White)
            showSpread -> Text("±${halfWidth.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = Color.White)
            else -> Text(stringResource(R.string.agreement_short, (agreement * 100).roundToInt()), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

private val HourColumnWidth = 58.dp

@Composable
fun HourlySection(hours: List<ConsensusHour>, phaseAt: (Instant) -> SunPhase, accent: Color, onHourClick: (Instant) -> Unit) {
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
        HourStrip(hours, phaseAt, accent, tagPrefix = "hour_column", onHourClick = onHourClick, modifier = Modifier.testTag("hourly_strip"))
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
    accent: Color,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    onHourClick: ((Instant) -> Unit)? = null,
    labelFirstAsNow: Boolean = true,
) {
    if (hours.isEmpty()) return
    val scroll = rememberScrollState()
    val formats = LocalFormats.current
    Column(modifier.horizontalScroll(scroll)) {
        TemperatureCurve(hours, accent, Modifier.width(HourColumnWidth * hours.size).height(90.dp))
        val openLabel = stringResource(R.string.open_hour_details)
        Row {
            hours.forEachIndexed { i, h ->
                // clickable is what merges a column into one spoken node, so the inert strip has to
                // say so itself — otherwise every hour is read as four unrelated fragments.
                val spoken = stringResource(
                    R.string.hour_column_desc, Format.hour(h.time, SouthTyrol.ZONE, formats),
                    h.condition.label(), Format.temp(h.tempC, formats), h.precipProb,
                    Format.mm(h.precipMm, formats),
                )
                val behaviour = if (onHourClick == null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
                else Modifier.clickable(onClickLabel = openLabel) { onHourClick(h.time) }
                Column(
                    Modifier.width(HourColumnWidth).then(behaviour)
                        .padding(vertical = 6.dp).testTag("${tagPrefix}_$i"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (i == 0 && labelFirstAsNow) stringResource(R.string.now) else Format.hour(h.time, SouthTyrol.ZONE, formats),
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Icon(painterResource(h.condition.iconRes(phaseAt(h.time))), contentDescription = h.condition.label(), tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(Format.temp(h.tempC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    PrecipBar(h.precipMm, h.precipProb, h.condition)
                }
            }
        }
    }
}

/**
 * Height is the probability, colour is the amount, and the number underneath is the amount in
 * millimetres. See [PrecipScale] for why they are split that way.
 */
@Composable
private fun PrecipBar(mm: Double, prob: Int, condition: Condition) {
    val formats = LocalFormats.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(18.dp).height(PrecipTrackHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(PrecipScale.TRACK),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (PrecipScale.isDrawn(prob)) {
                Box(
                    Modifier.fillMaxWidth()
                        // The floor is what keeps a real chance from rounding away to nothing.
                        .height((PrecipTrackHeight * PrecipScale.fillFraction(prob)).coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(PrecipScale.fillColor(mm, condition)),
                )
            }
        }
        Text(
            if (PrecipScale.hasAmount(mm)) Format.mm(mm, formats) else "",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color(0xFFB9D2F5),
        )
    }
}

/** The track every hour's bar is drawn inside, so a small chance reads as small, not as absent. */
private val PrecipTrackHeight = 26.dp

/** Consensus temperature line with translucent min/max band. */
@Composable
fun TemperatureCurve(hours: List<ConsensusHour>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val minT = hours.minOf { it.tempMinC } - 1
        val maxT = hours.maxOf { it.tempMaxC } + 1
        val colW = size.width / hours.size
        fun x(i: Int) = colW * i + colW / 2
        fun y(t: Double) = (size.height - 10f) - ((t - minT) / (maxT - minT)).toFloat() * (size.height - 20f)

        val band = Path().apply {
            moveTo(x(0), y(hours[0].tempMaxC))
            hours.forEachIndexed { i, h -> lineTo(x(i), y(h.tempMaxC)) }
            for (i in hours.indices.reversed()) lineTo(x(i), y(hours[i].tempMinC))
            close()
        }
        drawPath(band, Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.05f))))

        val line = Path().apply {
            moveTo(x(0), y(hours[0].tempC))
            for (i in 1 until hours.size) {
                val x0 = x(i - 1); val y0 = y(hours[i - 1].tempC); val x1 = x(i); val y1 = y(hours[i].tempC)
                cubicTo(x0 + colW / 2, y0, x1 - colW / 2, y1, x1, y1)
            }
        }
        drawPath(line, Color.White, style = Stroke(width = 3f))
        drawCircle(accent, radius = 5f, center = Offset(x(0), y(hours[0].tempC)))
    }
}

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
                    .heightIn(min = 48.dp) // the row's own content is well under the minimum touch target
                    .padding(vertical = 8.dp)
                    .testTag("day_row_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (i == 0) stringResource(R.string.today) else Format.weekday(d.date, formats),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.width(52.dp),
                )
                Icon(painterResource(d.condition.iconRes(SunPhase.DAY)), contentDescription = d.condition.label(), tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (d.precipMm >= 0.5) Format.mm(d.precipMm, formats) else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5), modifier = Modifier.width(48.dp))
                Text(Format.temp(d.minC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(36.dp))
                RangeBar(d.minC, d.maxC, globalMin, globalMax, accent, Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(Format.temp(d.maxC, formats), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
                AgreementDot(d.agreement, d.sourceCount)
            }
        }
        // Only worth saying once, and only when the list actually reaches that far.
        if (days.any { it.sourceCount == 1 }) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.daily_tail_note),
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
private fun AgreementDot(agreement: Float, sourceCount: Int) {
    val single = sourceCount == 1
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
