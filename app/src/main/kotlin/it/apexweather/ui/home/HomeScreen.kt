package it.apexweather.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb
import java.time.Instant
import java.time.LocalDate

@Composable
fun HomeScreen(onOpenBulletin: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state = state, onRefresh = viewModel::refresh, onOpenBulletin = onOpenBulletin)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(state: HomeUiState, onRefresh: () -> Unit, onOpenBulletin: () -> Unit) {
    var selectedHour by remember { mutableStateOf<Instant?>(null) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(state.isEmpty) { if (!state.isEmpty) appeared = true }
    val accent = Color.fromArgb(state.palette.accent)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize())
            state.isEmpty -> EmptyState(onRefresh, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.offline) item { OfflineBanner(state) }
                item { HeroSection(state) }
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) {
                        HourlySection(state.upcomingHours, state::phaseAt, accent) { selectedHour = it }
                    }
                }
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(600, 100)) + slideInVertically(tween(600, 100)) { it / 4 }) {
                        DailySection(state.days, accent) { selectedDay = it }
                    }
                }
                state.bulletin?.let { b ->
                    item {
                        AnimatedVisibility(appeared, enter = fadeIn(tween(700, 200)) + slideInVertically(tween(700, 200)) { it / 4 }) {
                            BulletinTeaser(b, onOpenBulletin)
                        }
                    }
                }
                item { AttributionFooter() }
            }
        }
    }

    // Both selections are resolved against the current state on every recomposition, so an open
    // sheet keeps up with the minute tick and closes itself once its hour or day rolls off the end.
    val hour = selectedHour?.let { t -> state.upcomingHours.firstOrNull { it.time == t } }
    if (hour != null) {
        ModalBottomSheet(onDismissRequest = { selectedHour = null }, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("hour_detail_sheet")) {
            HourDetail(hour, state)
        }
    }

    val day = selectedDay?.let { d -> state.days.firstOrNull { it.date == d } }
    if (day != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedDay = null },
            // A day carries far more than an hour, so it opens at full height rather than half.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("day_detail_sheet"),
        ) {
            DayDetail(day, state)
        }
    }
}

@Composable
private fun OfflineBanner(state: HomeUiState) {
    val text = state.updatedAt?.let { stringResource(R.string.offline_banner, Format.time(it, DorfTirol.ZONE)) } ?: stringResource(R.string.offline_banner_no_time)
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Color.White,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x66FF8A80)).padding(10.dp).testTag("offline_banner"),
    )
}

@Composable
private fun EmptyState(onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(32.dp).testTag("empty_state"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun HourDetail(hour: ConsensusHour, state: HomeUiState) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
        Text("${Format.time(hour.time, DorfTirol.ZONE)} · ${hour.condition.label()}", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.hour_summary, Format.temp(hour.tempC), Format.temp(hour.tempMinC), Format.temp(hour.tempMaxC), Format.mm(hour.precipMm), hour.precipProb, Format.wind(hour.windKmh, state.settings.windUnit)),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.per_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        hour.perSource.entries.sortedBy { it.key.ordinal }.forEach { (source, p) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(source.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                // KMOS has no wind parameter; showing its 0.0 would read as "calm".
                val wind = if (source == Source.SIAG_KMOS) "\u2013" else Format.wind(p.windKmh, state.settings.windUnit)
                Text("${Format.tempDecimal(p.tempC)}  ${Format.mm(p.precipMm)}  $wind", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}
