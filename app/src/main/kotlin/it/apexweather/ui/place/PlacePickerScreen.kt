package it.apexweather.ui.place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.domain.Place
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats

@Composable
fun PlacePickerScreen(onBack: () -> Unit, viewModel: PlacePickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlacePickerContent(
        state = state,
        onQuery = viewModel::onQuery,
        onPick = { viewModel.pick(it); onBack() },
        onFavourite = viewModel::setFavourite,
        onBack = onBack,
    )
}

/**
 * All 116 municipalities, searchable.
 *
 * A screen of its own rather than a sheet: a list this long with a search field above it deserves a
 * back-stack entry and the system back gesture, and a half-height sheet would open already folded.
 */
@Composable
fun PlacePickerContent(
    state: PlacePickerUiState,
    onQuery: (String) -> Unit,
    onPick: (String) -> Unit,
    onFavourite: (String, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    // The field owns its own text. Driving it from the state instead would send every keystroke
    // through a StateFlow and an asynchronous search before it came back to be drawn, and typing
    // faster than that round trip drops and reorders characters — "sterz" arrived as "terzs".
    var typed by rememberSaveable { mutableStateOf(state.query) }
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("place_picker")) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("place_back")) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Text(stringResource(R.string.place_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it; onQuery(it) },
            singleLine = true,
            label = { Text(stringResource(R.string.place_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("place_search"),
        )

        if (state.places.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    stringResource(R.string.place_none),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(32.dp).testTag("place_empty"),
                )
            }
            return@Column
        }

        // From the state's own membership set, never from the section above: the section is empty
        // while the reader is typing and a place does not stop being pinned because it was searched
        // for. See PlacePickerUiState.pinnedIstats.
        val pinned = state.pinnedIstats
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.favourites.isNotEmpty()) {
                item(key = "favourites_header") {
                    SectionHeader(stringResource(R.string.place_favourites))
                }
                items(state.favourites, key = { "fav_" + it.istat }) { place ->
                    PlaceRow(
                        place, locale, selected = place.istat == state.selected,
                        pinned = true, canPin = true, onPick = onPick, onFavourite = onFavourite,
                        tagPrefix = "place_fav_row",
                    )
                }
                item(key = "all_header") { SectionHeader(stringResource(R.string.place_all)) }
            }
            items(state.places, key = { it.istat }) { place ->
                PlaceRow(
                    place, locale, selected = place.istat == state.selected,
                    pinned = place.istat in pinned,
                    // A star that would be refused is drawn dim rather than absent: "you have used
                    // your four" is a different message from "this cannot be pinned".
                    canPin = state.canPinMore || place.istat in pinned,
                    onPick = onPick, onFavourite = onFavourite,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaceRow(
    place: Place,
    locale: java.util.Locale,
    selected: Boolean,
    pinned: Boolean = false,
    canPin: Boolean = true,
    onPick: (String) -> Unit,
    onFavourite: (String, Boolean) -> Unit = { _, _ -> },
    tagPrefix: String = "place_row",
) {
    val formats = LocalFormats.current
    val name = place.name(locale)
    val altitude = stringResource(R.string.unit_metres, Format.metres(place.altitudeM.toDouble(), formats))
    val district = stringResource(districtRes(place.district))
    val pinLabel = stringResource(if (pinned) R.string.place_unpin else R.string.place_pin, name)
    // Merged into one spoken node: three fragments read out separately are not a place.
    val spoken = stringResource(R.string.place_row_desc, name, altitude, district) +
        (if (selected) ", " + stringResource(R.string.place_selected) else "") +
        (if (pinned) ", " + stringResource(R.string.place_pinned) else "")
    Row(
        Modifier.fillMaxWidth()
            .clickable { onPick(place.istat) }
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("${tagPrefix}_${place.istat}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge, color = Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.place_meta, altitude, district),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(20.dp).testTag("place_selected_${place.istat}"),
            )
        }
        // Its own button rather than part of the row's tap target: the row chooses a place and this
        // keeps one, which are different enough that sharing a gesture would be a trap. `clearAndSet`
        // rather than the row's merge, or the star's own label is swallowed by the place's.
        IconButton(
            onClick = { onFavourite(place.istat, !pinned) },
            enabled = canPin,
            // The tag goes *inside* the block. `clearAndSetSemantics` clears the node's whole
            // config, so a `Modifier.testTag` further down the chain is cleared with everything
            // else and the button becomes unreachable by tag — which is why nothing tested the
            // star until the bug above was reported by hand.
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = pinLabel
                testTag = "place_pin_${place.istat}"
            },
        ) {
            Icon(
                if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (pinned) Color.White else Color.White.copy(alpha = if (canPin) 0.45f else 0.2f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The seven districts, named the way the reader's language names them. */
private fun districtRes(district: Int): Int = when (district) {
    1 -> R.string.district_1
    2 -> R.string.district_2
    3 -> R.string.district_3
    4 -> R.string.district_4
    5 -> R.string.district_5
    6 -> R.string.district_6
    else -> R.string.district_7
}
