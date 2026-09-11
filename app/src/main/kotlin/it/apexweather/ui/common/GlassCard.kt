package it.apexweather.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Translucent "frosted" card that lets the sky show through.
 *
 * It darkens what is behind it and never lightens it. The card used to be a white wash — 14 % down
 * to 6 % — which on a dawn sky lifted the background back above the contrast floor the palette had
 * just been held to, and put white text on it at 3,9:1. Black keeps the guarantee: whatever sky
 * passes behind the card passes on it too, because the card can only make it darker. What makes it
 * read as a panel is the hairline, not the wash.
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.12f))))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), shape)
            .padding(16.dp),
        content = content,
    )
}
