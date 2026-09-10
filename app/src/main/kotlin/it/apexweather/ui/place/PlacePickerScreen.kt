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
import androidx.compose.ui.semantics.contentDescription
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

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.places, key = { it.istat }) { place ->
                PlaceRow(place, locale, selected = place.istat == state.selected, onPick = onPick)
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, locale: java.util.Locale, selected: Boolean, onPick: (String) -> Unit) {
    val formats = LocalFormats.current
    val name = place.name(locale)
    val altitude = stringResource(R.string.unit_metres, Format.metres(place.altitudeM.toDouble(), formats))
    val district = stringResource(districtRes(place.district))
    // Merged into one spoken node: three fragments read out separately are not a place.
    val spoken = stringResource(R.string.place_row_desc, name, altitude, district) +
        if (selected) ", " + stringResource(R.string.place_selected) else ""
    Row(
        Modifier.fillMaxWidth()
            .clickable { onPick(place.istat) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("place_row_${place.istat}"),
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
