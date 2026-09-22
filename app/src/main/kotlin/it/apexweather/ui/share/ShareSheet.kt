package it.apexweather.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.ui.common.CompactLabel

/**
 * The preview the reader sees before anything leaves the phone.
 *
 * Two reasons it exists, one of them not about the reader at all. They are about to put this into
 * someone else's chat and should see it first. And `GraphicsLayer.toImageBitmap()` captures what was
 * actually composed and drawn — capturing something on screen is correct by construction, where
 * capturing something composed off-screen is a layout problem that would be found on somebody else's
 * phone rather than here. It costs one tap and removes a class of bug.
 *
 * The content scrolls, so **it has a cross**: `skipPartiallyExpanded` hands a downward drag to the
 * inner scroll first, and a sheet that uses it and has no cross is the "flickers, does not close"
 * the day sheet was.
 */
@Composable
fun ShareSheetContent(
    state: ShareCardState,
    layer: GraphicsLayer,
    onShare: () -> Unit,
    onClose: () -> Unit,
    /**
     * Null where the reader has already picked a day.
     *
     * Opening the preview from the day sheet means they asked about Thursday, and offering "7 Tage"
     * there would answer a question they did not ask.
     */
    onRange: ((ShareRange) -> Unit)? = null,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 32.dp)
            .testTag("share_sheet"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.share_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("share_sheet_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        if (onRange != null) {
            Spacer(Modifier.height(4.dp))
            RangeChips(state.range, onRange)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            // What is recorded is exactly what is drawn here — the same call, one frame, no second
            // composition of the card with a different density or a different palette behind it.
            ShareCard(
                state,
                Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth().testTag("share_sheet_send"),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.share_action))
        }
    }
}

/**
 * How far the picture reaches.
 *
 * `icon = {}` because Material reserves about 24 dp for a tick in *every* segment whether it is
 * shown or not, and the fill already says which one is on; `CompactLabel` because these labels sit
 * in a control whose width is not their own, and "7 Tage" must not wrap inside its own segment at a
 * 2x font scale — the same rule the comparison and settings screens are held to.
 */
@Composable
private fun RangeChips(selected: ShareRange, onRange: (ShareRange) -> Unit) {
    val labels = mapOf(
        ShareRange.TODAY to R.string.share_range_today,
        ShareRange.THREE_DAYS to R.string.share_range_3,
        ShareRange.SEVEN_DAYS to R.string.share_range_7,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("share_range_chips")) {
        ShareRange.entries.forEachIndexed { i, range ->
            SegmentedButton(
                selected = selected == range,
                onClick = { onRange(range) },
                shape = SegmentedButtonDefaults.itemShape(i, ShareRange.entries.size),
                icon = {},
                modifier = Modifier.testTag("share_range_${range.name}"),
            ) {
                CompactLabel {
                    Text(stringResource(labels.getValue(range)), maxLines = 1)
                }
            }
        }
    }
}

/** The plain-text form that rides along for a target that cannot take an image. */
fun shareText(place: String, temp: String, condition: String): String = "$place · $temp · $condition"
