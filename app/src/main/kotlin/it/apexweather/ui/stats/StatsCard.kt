package it.apexweather.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.data.WindUnit
import it.apexweather.domain.Contender
import it.apexweather.domain.Quantity
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.SourceColors

/** The comparison screen's teaser: the three best models for temperature, and the way in. */
@Composable
fun StatsCard(state: StatsCardState, windUnit: WindUnit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val f = LocalFormats.current
    val col = statsColumnWidth()
    GlassCard(
        modifier.testTag("stats_card").clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onOpen),
    ) {
        Text(stringResource(R.string.stats_card_title), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Text(stringResource(R.string.stats_card_subtitle), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        // Nothing is claimed while the history is still being read.
        when {
            state.loading -> Unit
            !state.hasStation -> Text(stringResource(R.string.stats_no_station), style = MaterialTheme.typography.bodyMedium, color = Color.White)
            !state.enoughData -> Text(stringResource(R.string.stats_not_enough), style = MaterialTheme.typography.bodyMedium, color = Color.White)
            else -> state.top.forEach { row ->
                val source = (row.contender as? Contender.Model)?.source ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.rank?.let { f.whole(it) }.orEmpty(), Modifier.width(28.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(source)))
                    Spacer(Modifier.width(8.dp))
                    Text(source.shortName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(mainValue(Quantity.TEMPERATURE, row.score, windUnit, f), Modifier.width(col), style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.End)
                    Text(percent(row.score.hitRate, f), Modifier.width(col), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.End)
                }
            }
        }
        TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("stats_card_open")) {
            Text(stringResource(R.string.stats_card_all))
        }
    }
}
