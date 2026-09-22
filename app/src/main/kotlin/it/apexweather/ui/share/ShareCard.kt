package it.apexweather.ui.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.SunPhase
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.label
import it.apexweather.ui.home.SingleModelColor
import it.apexweather.ui.home.agreementColor
import it.apexweather.ui.home.PrecipScale
import it.apexweather.ui.theme.fromArgb

/** The card's width. Fixed: it is a picture, not a screen. */
val ShareCardWidth: Dp = 360.dp

/**
 * The picture that gets shared.
 *
 * **It is drawn at `fontScale = 1f` whatever the reader's text size is**, and that is not a
 * shortcut. Everywhere else in this app a larger text setting is honoured all the way down —
 * `CompactLabel` exists precisely because two controls could not do that and had to be capped. Here
 * the output is a bitmap that leaves the phone: the reader's text size is a fact about *their*
 * screen, and the person receiving the image has their own. At a 2x scale the eight columns of the
 * strip would not fit the fixed width and the card would clip, so honouring the setting would not
 * even serve the reader who set it. The whole card therefore composes under a density with the
 * scale pinned, and [ShareSheetTest] asserts the measured width does not move at 2x.
 *
 * The gradient is the place's own sky, which has already been through
 * `SkyContrast.darkenForWhiteText` by the time it reaches [ShareCardState] — so the white text on it
 * carries 4,5:1 for free. Do not introduce a second palette here; it would be the one palette in the
 * app not covered by `SkyContrastTest`.
 */
@Composable
fun ShareCard(state: ShareCardState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        ShareCardBody(state, modifier)
    }
}

@Composable
private fun ShareCardBody(state: ShareCardState, modifier: Modifier) {
    val formats = LocalFormats.current
    val accent = Color.fromArgb(state.palette.accent)
    Column(
        modifier
            .width(ShareCardWidth)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.fromArgb(state.palette.top),
                        Color.fromArgb(state.palette.mid),
                        Color.fromArgb(state.palette.bottom),
                    ),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag("share_card"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.placeName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f).testTag("share_card_place"),
            )
            Text(
                "${Format.weekday(state.date, formats)} ${Format.dayMonth(state.date, formats)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }

        Spacer(Modifier.height(10.dp))

        // The number and the picture are the two halves of one answer, as they are on the hero.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.tempC?.let { Format.temp(it, formats) } ?: "–",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 68.sp, letterSpacing = (-2).sp),
                color = Color.White,
                modifier = Modifier.testTag("share_card_temp"),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                painterResource(state.condition.iconRes(state.phase)),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(76.dp),
            )
        }

        val range = if (state.maxC != null && state.minC != null) {
            " · ${Format.temp(state.maxC, formats)} / ${Format.temp(state.minC, formats)}"
        } else {
            ""
        }
        Text(
            state.condition.label() + range,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.testTag("share_card_condition"),
        )

        RainLine(state)
        AdjustmentLine(state)

        Spacer(Modifier.height(14.dp))
        // One or the other; see ShareCardStateBuilder for why they are never stacked.
        if (state.range == ShareRange.TODAY) HourRow(state) else DayRows(state)
        Spacer(Modifier.height(16.dp))
        Footer(state)
    }
}

/** The one line on the card that is weather rather than provenance, so it sits directly under it. */
@Composable
private fun RainLine(state: ShareCardState) {
    val formats = LocalFormats.current
    // The word follows the condition, for the reason `Format.showsSnow` exists: a snow icon over
    // "Regenwahrscheinlichkeit" is a title and a body disagreeing about one hour, and this one
    // travels past the screen that could have corrected it.
    val frozen = state.condition.isFrozen
    val text = when {
        state.precipStart != null -> {
            val at = Format.time(state.precipStart, SouthTyrol.ZONE, formats)
            val withChance = if (frozen) R.string.share_snow_from_with_chance else R.string.share_rain_from_with_chance
            val plain = if (frozen) R.string.share_snow_from else R.string.share_rain_from
            state.precipProb?.let { stringResource(withChance, at, it) } ?: stringResource(plain, at)
        }
        state.precipProb != null && state.precipProb > 0 ->
            stringResource(if (frozen) R.string.share_snow_chance else R.string.share_rain_chance, state.precipProb)
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 4.dp).testTag("share_card_rain"),
    )
}

/**
 * A moved reading says so here exactly as it does on the hero, and for a stronger reason: this
 * picture travels past the screen that would otherwise carry the caveat.
 */
@Composable
private fun AdjustmentLine(state: ShareCardState) {
    val formats = LocalFormats.current
    val delta = state.adjustmentC ?: return
    if (Format.temp(delta, formats) == Format.temp(0.0, formats)) return
    Text(
        stringResource(R.string.share_from_station_adjusted, Format.tempDelta(delta, formats)),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 2.dp).testTag("share_card_adjustment"),
    )
}

/**
 * The strip, at a fixed number of equal columns rather than the home screen's scrolling one: a
 * picture cannot be scrolled, so every column that is drawn has to fit.
 */
@Composable
private fun HourRow(state: ShareCardState) {
    if (state.hours.isEmpty()) return
    val formats = LocalFormats.current
    Row(
        Modifier.fillMaxWidth().testTag("share_card_hours"),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        state.hours.forEach { h ->
            val snow = PrecipScale.showsSnow(h.snowCm, h.condition)
            val amount = if (snow) h.snowCm ?: 0.0 else h.precipMm
            val fill = if (snow) PrecipScale.snowFillFraction(amount) else PrecipScale.fillFraction(h.precipMm)
            val has = if (snow) amount > 0.0 else PrecipScale.hasAmount(h.precipMm)
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                CompactText(Format.hour(h.time, SouthTyrol.ZONE, formats), alpha = 0.7f)
                Icon(
                    painterResource(h.condition.iconRes(h.phase)),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
                CompactText(Format.temp(h.tempC, formats), alpha = 1f, weight = FontWeight.Medium)
                Box(
                    Modifier.width(8.dp).height(BarTrack).clip(RoundedCornerShape(4.dp)).background(PrecipScale.TRACK),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (has) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(BarTrack * fill)
                                .clip(RoundedCornerShape(4.dp))
                                .background(PrecipScale.fillColor(h.precipMm, h.condition)),
                        )
                    }
                }
                CompactText(
                    if (has) Format.precip(h.precipMm, h.snowCm, h.condition, formats) else "",
                    alpha = 0.7f,
                )
            }
        }
    }
}

private val BarTrack = 26.dp

/**
 * The week, in the day list's own layout and the day list's own vocabulary.
 *
 * Same order as `DailySection` — weekday, icon, amount over chance, low, range, high, dot — because
 * a reader who has learnt one should not have to learn the other, and a shared image is the worst
 * place to introduce private notation.
 *
 * The rows are sized by their content rather than by `DayRowMinHeight`. That constant is 44 dp
 * because a finger has to hit the row and open a sheet; there are no fingers in a PNG.
 */
@Composable
private fun DayRows(state: ShareCardState) {
    if (state.days.isEmpty()) return
    val formats = LocalFormats.current
    val accent = Color.fromArgb(state.palette.accent)
    // Normalised across the days actually shown, so the bars compare with each other and with
    // nothing else — which is what makes a week scannable at a glance.
    val low = state.days.minOf { it.minC }
    val high = state.days.maxOf { it.maxC }
    Column(Modifier.fillMaxWidth().testTag("share_card_days")) {
        state.days.forEachIndexed { i, d ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (i == 0) stringResource(R.string.today) else Format.weekday(d.date, formats),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.width(46.dp),
                )
                Icon(
                    painterResource(d.condition.iconRes(SunPhase.DAY)),
                    contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.width(44.dp)) {
                    val frozen = Format.showsSnow(d.snowCm, d.condition)
                    Text(
                        when {
                            frozen -> Format.precip(d.precipMm, d.snowCm, d.condition, formats)
                            d.precipMm >= 0.5 -> Format.mm(d.precipMm, formats)
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color(0xFFB9D2F5),
                        maxLines = 1,
                    )
                    Text(
                        if (d.precipProb > 0) stringResource(R.string.unit_percent, d.precipProb) else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color(0xFFB9D2F5).copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                Text(
                    Format.temp(d.minC, formats),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.width(32.dp),
                )
                ShareRangeBar(d.minC, d.maxC, low, high, accent, Modifier.weight(1f).height(5.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    Format.temp(d.maxC, formats),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp),
                )
                // The day list's dot, unchanged. This is the whole reason a week is defensible on a
                // picture: ApexWidget stops at five days because a widget has nowhere to put the
                // badge or its explanation, and a card has room for both.
                val single = d.sourceCount == 1 && !d.ensembleBacked
                Box(
                    Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape)
                        .background(if (single) SingleModelColor else agreementColor(d.agreement)),
                )
            }
        }
        // Said once, and only where the data says it: a coloured dot on a single-model day has to be
        // accounted for, and a grey one has to be explained.
        if (state.hasSingleModelDay) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (state.singleModelDaysAreEnsembleBacked) R.string.daily_tail_note_ensemble
                    else R.string.daily_tail_note,
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.testTag("share_card_tail_note"),
            )
        }
    }
}

/** The day list's range bar, at the card's own proportions. */
@Composable
private fun ShareRangeBar(min: Double, max: Double, low: Double, high: Double, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (high - low).coerceAtLeast(1.0)
        val x0 = ((min - low) / span * size.width).toFloat()
        val x1 = ((max - low) / span * size.width).toFloat()
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF8FB3E8), accent), startX = x0, endX = x1),
            topLeft = Offset(x0, 0f),
            size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
            cornerRadius = CornerRadius(size.height / 2),
        )
    }
}

@Composable
private fun CompactText(text: String, alpha: Float, weight: FontWeight? = null) {
    Text(
        text,
        style = LocalTextStyle.current.copy(fontSize = 9.sp, fontWeight = weight),
        color = Color.White.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * Two lines, and the second is a licence obligation rather than a flourish.
 *
 * Sharing the picture redistributes the data in it, and GeoSphere Austria's is CC BY 4.0 — the same
 * licence that put `map_attribution` along the bottom of the map. `R.string.attribution` is too long
 * for a card this size, so [R.string.share_card_attribution] is the short form that still names
 * everyone the licences require. It does not get dropped to make room.
 */
@Composable
private fun Footer(state: ShareCardState) {
    Text(
        stringResource(R.string.app_name) +
            if (state.sourceCount > 0) " · " + pluralStringResource(R.plurals.share_card_models, state.sourceCount, state.sourceCount) else "",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.8f),
        modifier = Modifier.testTag("share_card_footer"),
    )
    Text(
        stringResource(R.string.share_card_attribution),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 2.dp),
    )
}
