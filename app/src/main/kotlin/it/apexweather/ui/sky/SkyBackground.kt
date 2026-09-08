package it.apexweather.ui.sky

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.theme.fromArgb

/**
 * Full-screen animated sky: crossfading gradient, weather particles, ridge silhouette.
 * Particles only run while the lifecycle is RESUMED, [animationsEnabled] is true and the system
 * is not asking for reduced motion. The gradient crossfade always runs: it is a colour change,
 * not motion.
 */
@Composable
fun SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val spec = tween<Color>(1500)
    val top by animateColorAsState(Color.fromArgb(palette.top), spec, label = "top")
    val mid by animateColorAsState(Color.fromArgb(palette.mid), spec, label = "mid")
    val bottom by animateColorAsState(Color.fromArgb(palette.bottom), spec, label = "bottom")
    val ridge by animateColorAsState(Color.fromArgb(palette.ridge), spec, label = "ridge")
    val accent = Color.fromArgb(palette.accent)

    val system = remember(palette.particle, palette.density) { ParticleSystem(palette.particle, palette.density) }
    var frame by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Developer options "animator duration scale = off" and the accessibility "remove animations"
    // setting both zero this scale; either one means the user asked for no motion.
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) == 0f
    }

    if (animationsEnabled && !reduceMotion) {
        LaunchedEffect(system, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                        last = now
                        system.step(dt)
                        frame = now
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxSize().testTag("sky").background(Brush.verticalGradient(listOf(top, mid, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val f = frame // read state so the canvas redraws every frame
            system.draw(this, accent)
            drawRidge(ridge)
        }
    }
}

/** Stylized Meran valley ridge (Ifinger / Mutspitze) at the bottom of the screen. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRidge(color: Color) {
    val w = size.width; val h = size.height
    val pts = listOf(0f to 0.86f, 0.08f to 0.80f, 0.16f to 0.84f, 0.26f to 0.74f, 0.33f to 0.79f, 0.42f to 0.70f,
        0.50f to 0.76f, 0.58f to 0.72f, 0.66f to 0.81f, 0.74f to 0.77f, 0.84f to 0.85f, 0.92f to 0.82f, 1f to 0.88f)
    val path = Path().apply {
        moveTo(0f, h)
        pts.forEach { (x, y) -> lineTo(x * w, y * h) }
        lineTo(w, h); close()
    }
    drawPath(path, color.copy(alpha = 0.85f))
    val back = Path().apply {
        moveTo(0f, h)
        listOf(0f to 0.78f, 0.12f to 0.70f, 0.22f to 0.75f, 0.36f to 0.62f, 0.48f to 0.69f, 0.60f to 0.60f, 0.72f to 0.68f, 0.86f to 0.64f, 1f to 0.74f)
            .forEach { (x, y) -> lineTo(x * w, y * h) }
        lineTo(w, h); close()
    }
    drawPath(back, color.copy(alpha = 0.45f))
}
