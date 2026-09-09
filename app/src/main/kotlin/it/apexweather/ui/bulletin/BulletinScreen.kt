package it.apexweather.ui.bulletin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.GlassCard

@Composable
fun BulletinScreen(viewModel: BulletinViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BulletinContent(state)
}

@Composable
fun BulletinContent(state: BulletinUiState) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val locale = LocalConfiguration.current.locales[0]
    val formats = LocalFormats.current
    val b = state.bulletin
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(R.string.bulletin_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp)) }
        if (b == null) {
            item { Text(stringResource(R.string.bulletin_empty), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(24.dp).testTag("bulletin_empty")) }
            return@LazyColumn
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.bulletin_issued, Format.timestamp(b.issuedAt, DorfTirol.ZONE, state.now, formats)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
                Text(b.evolution, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.testTag("bulletin_text"))
            }
        }
        if (b.days.isNotEmpty()) item {
            Column {
                Text(stringResource(R.string.bulletin_district), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                // A plain scrolling Row, not a LazyRow: there are only ever a handful of district
                // days, and lazy items are measured independently, so IntrinsicSize.Max — which is
                // what keeps every card as tall as the tallest — cannot see its siblings there.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    b.days.forEachIndexed { i, d ->
                        GlassCard(Modifier.width(150.dp).fillMaxHeight().testTag("bulletin_day_$i")) {
                            Text(Format.weekday(d.date, formats) + " " + Format.dayMonth(d.date, formats), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The slot is always 44 dp, so a day without an icon keeps the
                                // temperatures in the same column as its neighbours.
                                Box(Modifier.size(44.dp)) {
                                    d.iconUrl?.let { AsyncImage(model = it, contentDescription = d.description, modifier = Modifier.size(44.dp)) }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(listOfNotNull(d.minC?.let { Format.temp(it, formats) }, d.maxC?.let { Format.temp(it, formats) }).joinToString(" / "), style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    if (d.rainToMm != null && d.rainToMm > 0) Text("${d.rainFromMm?.toInt() ?: 0}–${d.rainToMm.toInt()} mm", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(d.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 3)
                        }
                    }
                }
            }
        }
        itemsIndexed(b.conditions) { _, c ->
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(Format.weekday(c.date, formats) + " · " + c.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(c.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                c.temperatures?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f)) }
                c.mapImageUrl?.let {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(model = it, contentDescription = c.title, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
                }
            }
        }
    }
}
