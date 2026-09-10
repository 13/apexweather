package it.apexweather.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.data.WindUnit
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import java.time.Instant

/**
 * What the weather station at Meran actually measured, next to what the models predict.
 *
 * Everything on this card is an observation, never a forecast: the wind here is wind that blew, the
 * pressure a reading nobody modelled. The station's own timestamp is on the card, because a
 * measurement from two hours ago is still worth reading as long as it says so — that is why this
 * card takes the reading unfiltered while the hero temperature keeps its ninety-minute cutoff.
 *
 * The freezing level sits here rather than with the forecast because it answers the same question
 * the reader came to this card with: what is happening outside, right now, at this altitude.
 */
@Composable
fun StationSection(
    station: StationObservation?,
    currentHour: ConsensusHour?,
    windUnit: WindUnit,
    now: Instant,
) {
    if (station == null) return
    val formats = LocalFormats.current
    // A station row with nothing but a name is not worth a card.
    val rows = buildList {
        station.tempC?.let { add(R.string.station_temp to Format.tempDecimal(it, formats)) }
        station.windKmh?.let { w ->
            val wind = Format.wind(w, windUnit, formats)
            add(R.string.station_wind to (station.windDir?.let { stringResource(R.string.station_wind_with_dir, wind, it) } ?: wind))
        }
        station.gustKmh?.let { add(R.string.station_gust to Format.wind(it, windUnit, formats)) }
        station.humidityPct?.let { add(R.string.station_humidity to stringResource(R.string.unit_percent, it)) }
        station.pressureHpa?.let { add(R.string.station_pressure to stringResource(R.string.unit_hpa, Format.hPa(it, formats))) }
        station.precipMm?.let { add(R.string.station_precip to Format.mm(it, formats)) }
    }
    if (rows.isEmpty()) return

    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("station_card")) {
        Text(
            stringResource(R.string.section_station, station.stationName),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        rows.forEach { (labelRes, value) ->
            val label = stringResource(labelRes)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
                    .testTag("station_row_${labelRes}"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.station_measured_at, Format.timestamp(station.time, SouthTyrol.ZONE, now, formats)),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.testTag("station_measured_at"),
        )
        currentHour?.freezingLevelM?.let {
            Text(
                freezingLevelText(it, formats),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.testTag("freezing_level"),
            )
        }
    }
}

/**
 * Dorf Tirol sits at about 600 m and the valley floor at 300 m. Below 1500 m the number stops being
 * trivia and starts deciding whether the next precipitation arrives as rain or as snow, so it is
 * worded as a snow line there and as a plain isotherm height above.
 */
@Composable
fun freezingLevelText(metres: Double, formats: it.apexweather.ui.common.Formats): String {
    val height = stringResource(R.string.unit_metres, Format.metres(metres, formats))
    return if (metres < SNOW_LINE_MATTERS_BELOW) stringResource(R.string.freezing_level_low, height)
    else stringResource(R.string.freezing_level, height)
}

private const val SNOW_LINE_MATTERS_BELOW = 1500.0
