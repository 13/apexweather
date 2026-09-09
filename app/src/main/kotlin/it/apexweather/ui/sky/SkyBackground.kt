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
import androidx.compose.ui.geometry.Size
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
    // The canvas redraws at display rate, but the ridge is fixed geometry: build it once per size.
    val ridgePaths = remember { RidgePaths() }
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
            ridgePaths.update(size)
            drawPath(ridgePaths.front, ridge.copy(alpha = 0.85f))
            drawPath(ridgePaths.back, ridge.copy(alpha = 0.45f))
        }
    }
}

/**
 * Stylized Meran valley ridge (Ifinger / Mutspitze) at the bottom of the screen, as alternating
 * x/y fractions of the canvas.
 */
private val RIDGE_FRONT = floatArrayOf(
    0f, 0.86f, 0.08f, 0.80f, 0.16f, 0.84f, 0.26f, 0.74f, 0.33f, 0.79f, 0.42f, 0.70f,
    0.50f, 0.76f, 0.58f, 0.72f, 0.66f, 0.81f, 0.74f, 0.77f, 0.84f, 0.85f, 0.92f, 0.82f, 1f, 0.88f,
)

/** The paler range behind it, drawn higher up. */
private val RIDGE_BACK = floatArrayOf(
    0f, 0.78f, 0.12f, 0.70f, 0.22f, 0.75f, 0.36f, 0.62f, 0.48f, 0.69f, 0.60f, 0.60f,
    0.72f, 0.68f, 0.86f, 0.64f, 1f, 0.74f,
)

/**
 * The two ridge silhouettes, rebuilt only when the canvas changes size. Building them inside the
 * draw lambda cost two paths and two lists of pairs on every frame, for geometry that never moves.
 */
private class RidgePaths {
    val front = Path()
    val back = Path()
    private var builtFor: Size = Size.Unspecified

    fun update(size: Size) {
        if (size == builtFor) return
        builtFor = size
        fill(front, RIDGE_FRONT, size)
        fill(back, RIDGE_BACK, size)
    }

    private fun fill(path: Path, points: FloatArray, size: Size) {
        path.reset()
        path.moveTo(0f, size.height)
        var i = 0
        while (i < points.size) {
            path.lineTo(points[i] * size.width, points[i + 1] * size.height)
            i += 2
        }
        path.lineTo(size.width, size.height)
        path.close()
    }
}
