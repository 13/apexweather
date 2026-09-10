package it.apexweather.ui.sky

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.theme.fromArgb

/**
 * Full-screen animated sky: crossfading gradient and weather particles.
 * Both the particles and the gradient's crossfade run only while [animationsEnabled] is true and the
 * system is not asking for reduced motion; the particles additionally need the lifecycle RESUMED.
 * With motion off the sky is still the right colour for the hour — it arrives in one frame.
 */
@Composable
fun SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
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
    // One decision for both kinds of movement. The crossfade used to run regardless, on the grounds
    // that a colour change is not motion — but a reader who switches "animate the sky" off and then
    // watches it fade for a second and a half has been told one thing and shown another.
    val motion = animationsEnabled && !reduceMotion

    val spec: AnimationSpec<Color> = if (motion) tween(1500) else snap()
    val top by animateColorAsState(Color.fromArgb(palette.top), spec, label = "top")
    val mid by animateColorAsState(Color.fromArgb(palette.mid), spec, label = "mid")
    val bottom by animateColorAsState(Color.fromArgb(palette.bottom), spec, label = "bottom")
    val accent = Color.fromArgb(palette.accent)

    if (motion) {
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
        }
    }
}
