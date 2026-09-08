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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.icon
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb
import kotlin.math.roundToInt

@Composable
fun HeroSection(state: HomeUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.Start) {
        Text(DorfTirol.NAME, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
        Text(
            text = state.heroTempC?.let(Format::temp) ?: "–",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.testTag("hero_temp"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(state.heroCondition.icon(state.phase), contentDescription = null, tint = Color.fromArgb(state.palette.accent), modifier = Modifier.size(22.dp))
            Text(state.heroCondition.label(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.heroFeelsLikeC?.let { Text(stringResource(R.string.feels_like, Format.temp(it)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)) }
            state.bandHalfWidth?.let { AgreementBadge(it, state.currentHour?.agreement ?: 0.5f) }
        }
        Spacer(Modifier.height(4.dp))
        val source = state.observation?.let { stringResource(R.string.now_from_station, it.stationName, Format.time(it.time, DorfTirol.ZONE)) }
            ?: stringResource(R.string.now_from_consensus, state.currentHour?.sourceCount ?: 0)
        Text(source, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
        state.updatedAt?.let {
            Text(stringResource(R.string.updated_at, Format.time(it, DorfTirol.ZONE)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun AgreementBadge(halfWidth: Double, agreement: Float) {
    val color = when {
        agreement >= 0.75f -> Color(0xFF7CE0A5)
        agreement >= 0.45f -> Color(0xFFFFD166)
        else -> Color(0xFFFF8A80)
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("agreement_badge"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("±${halfWidth.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private val HourColumnWidth = 58.dp

@Composable
fun HourlySection(hours: List<ConsensusHour>, phaseAt: (java.time.Instant) -> SunPhase, accent: Color, onHourClick: (Int) -> Unit) {
    if (hours.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.section_hourly), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll).testTag("hourly_strip")) {
            TemperatureCurve(hours, accent, Modifier.width(HourColumnWidth * hours.size).height(90.dp))
            Row {
                hours.forEachIndexed { i, h ->
                    Column(
                        Modifier.width(HourColumnWidth).clickable { onHourClick(i) }.padding(vertical = 6.dp).testTag("hour_column_$i"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (i == 0) stringResource(R.string.now) else Format.hour(h.time, DorfTirol.ZONE), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                        Spacer(Modifier.height(6.dp))
                        Icon(h.condition.icon(phaseAt(h.time)), null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(Format.temp(h.tempC), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        PrecipBar(h.precipMm, h.precipProb)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrecipBar(mm: Double, prob: Int) {
    val heightFraction = (mm / 5.0).coerceIn(0.0, 1.0).toFloat()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(18.dp).height(22.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.width(18.dp).height((22 * heightFraction).dp.coerceAtLeast(if (mm > 0.05) 2.dp else 0.dp)).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8FB3E8)))
        }
        Text(if (prob > 0) "$prob%" else "", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color(0xFFB9D2F5))
    }
}

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
fun DailySection(days: List<ConsensusDay>, phase: SunPhase, accent: Color) {
    if (days.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val globalMin = days.minOf { it.minC }
    val globalMax = days.maxOf { it.maxC }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("daily_list")) {
        Text(stringResource(R.string.section_daily), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i == 0) stringResource(R.string.today) else Format.weekday(d.date, locale),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.width(52.dp),
                )
                Icon(d.condition.icon(SunPhase.DAY), null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (d.precipMm >= 0.5) Format.mm(d.precipMm) else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5), modifier = Modifier.width(48.dp))
                Text(Format.temp(d.minC), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(36.dp))
                RangeBar(d.minC, d.maxC, globalMin, globalMax, accent, Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(Format.temp(d.maxC), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
                AgreementDot(d.agreement)
            }
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

@Composable
private fun AgreementDot(agreement: Float) {
    val color = when { agreement >= 0.75f -> Color(0xFF7CE0A5); agreement >= 0.45f -> Color(0xFFFFD166); else -> Color(0xFFFF8A80) }
    Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(color))
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
