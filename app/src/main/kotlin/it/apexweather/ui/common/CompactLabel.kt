package it.apexweather.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * A label inside a control whose width is decided by something other than the label.
 *
 * Two of those in this app, and both broke in the same way at a 2x font scale on a 384 dp screen:
 *
 * - **The navigation bar.** Five items share the width, so one gets about 77 dp whatever the text
 *   says. "Vergleich" needs about 60 dp at the ordinary size and 120 at twice it, so at 2x it wrapped
 *   to "Vergl / eich" and collided with the label beside it — measured on the phone at 64 dp tall
 *   against the 32 it is given.
 * - **The segmented buttons** on the comparison screen and in settings, where "Niederschlag" and
 *   "System" wrapped inside their own segment and pushed the pill out of shape.
 *
 * There is no layout that fixes this. A fifth of 384 dp is a fifth of 384 dp, and a word that needs
 * twice that will not fit however it is arranged; something has to give. Three things could:
 *
 * - *Ellipsis* — "Vergl…", "Beric…" — which is legible and says nothing.
 * - *Dropping the labels* at large text sizes, leaving the icons alone. That takes the most from
 *   exactly the reader who turned the text size up.
 * - *Capping how far the label itself scales*, which is what this does and what the platform's own
 *   navigation and tab bars do. The icon beside it still grows with the system setting, the whole
 *   word stays on screen, and what is lost is some of the label's growth rather than the label.
 *
 * [MAX_FONT_SCALE] is measured rather than chosen: "Vergleich" at 1,2x is about 72 dp against the
 * 77 an item has, and the longest segmented-button label, "Niederschlag", is about 101 dp against
 * 117. At 1,3 the first of those no longer fits.
 *
 * It is a ceiling, never a floor: a reader who has made the text *smaller* gets exactly what they
 * asked for. Everything else on the screen — every heading, every temperature, the body of the
 * bulletin — is untouched by this and scales the whole way.
 */
@Composable
fun CompactLabel(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= MAX_FONT_SCALE) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, MAX_FONT_SCALE),
        content = content,
    )
}

/** How far a label in a fixed-width control is allowed to grow. See [CompactLabel]. */
const val MAX_FONT_SCALE = 1.2f
