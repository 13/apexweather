package it.apexweather.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import it.apexweather.domain.ParticleKind
import kotlin.math.sin
import kotlin.random.Random

/** One particle in normalized [0,1] space. */
class Particle(var x: Float, var y: Float, val size: Float, val speed: Float, val seed: Float)

class ParticleSystem(val kind: ParticleKind, density: Float, private val random: Random = Random(42)) {
    val particles: List<Particle>
    var lightningFlash = 0f // 0..1, decays each frame
    private var nextStrikeAt = 2f
    private var elapsed = 0f

    init {
        val count = when (kind) {
            ParticleKind.NONE -> 0
            ParticleKind.STARS -> (90 * density).toInt().coerceAtLeast(20)
            ParticleKind.CLOUDS -> (5 * density).toInt().coerceAtLeast(2)
            ParticleKind.RAIN -> (180 * density).toInt().coerceAtLeast(30)
            ParticleKind.SNOW -> (120 * density).toInt().coerceAtLeast(25)
            ParticleKind.FOG -> 4 // intentionally fixed; independent of density
            ParticleKind.LIGHTNING -> (160 * density).toInt().coerceAtLeast(40)
        }
        particles = List(count) { newParticle(kind, random, spawnAnywhere = true) }
    }

    private fun newParticle(kind: ParticleKind, r: Random, spawnAnywhere: Boolean): Particle {
        val y = if (spawnAnywhere) r.nextFloat() else -0.05f
        return when (kind) {
            ParticleKind.STARS -> Particle(r.nextFloat(), r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 1.6f, 0f, r.nextFloat() * 6.28f)
            ParticleKind.CLOUDS -> Particle(if (spawnAnywhere) r.nextFloat() else -0.3f, 0.05f + r.nextFloat() * 0.35f, 0.25f + r.nextFloat() * 0.25f, 0.004f + r.nextFloat() * 0.006f, r.nextFloat())
            ParticleKind.RAIN, ParticleKind.LIGHTNING -> Particle(r.nextFloat(), y, 8f + r.nextFloat() * 14f, 0.9f + r.nextFloat() * 0.6f, r.nextFloat())
            ParticleKind.SNOW -> Particle(r.nextFloat(), y, 1.5f + r.nextFloat() * 3f, 0.06f + r.nextFloat() * 0.08f, r.nextFloat() * 6.28f)
            ParticleKind.FOG -> Particle(r.nextFloat(), 0.3f + r.nextFloat() * 0.6f, 0.5f + r.nextFloat() * 0.5f, 0.01f + r.nextFloat() * 0.01f, r.nextFloat())
            ParticleKind.NONE -> Particle(0f, 0f, 0f, 0f, 0f)
        }
    }

    /** Advance by [dt] seconds. */
    fun step(dt: Float) {
        elapsed += dt
        for (p in particles) {
            when (kind) {
                ParticleKind.RAIN, ParticleKind.LIGHTNING -> { p.y += p.speed * dt; p.x -= 0.08f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.SNOW -> { p.y += p.speed * dt; p.x += sin(elapsed * 0.8f + p.seed) * 0.02f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.CLOUDS, ParticleKind.FOG -> { p.x += p.speed * dt; if (p.x > 1.3f) p.x = -0.3f }
                ParticleKind.STARS, ParticleKind.NONE -> Unit
            }
        }
        if (kind == ParticleKind.LIGHTNING) {
            lightningFlash = (lightningFlash - dt * 2.5f).coerceAtLeast(0f)
            if (elapsed >= nextStrikeAt) { lightningFlash = 1f; nextStrikeAt = elapsed + 3f + random.nextFloat() * 6f }
        }
    }

    fun draw(scope: DrawScope, accent: Color) = with(scope) {
        val w = size.width; val h = size.height
        when (kind) {
            ParticleKind.STARS -> particles.forEach { p ->
                val twinkle = 0.55f + 0.45f * sin(elapsed * 1.5f + p.seed)
                drawCircle(accent.copy(alpha = 0.9f * twinkle), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.CLOUDS -> particles.forEach { p ->
                val cw = p.size * w; val ch = cw * 0.35f
                val c = Color.White.copy(alpha = 0.10f)
                drawOval(c, topLeft = Offset(p.x * w - cw / 2, p.y * h - ch / 2), size = Size(cw, ch))
                drawOval(c, topLeft = Offset(p.x * w - cw * 0.3f, p.y * h - ch * 0.9f), size = Size(cw * 0.6f, ch * 1.2f))
            }
            ParticleKind.RAIN, ParticleKind.LIGHTNING -> {
                particles.forEach { p ->
                    val x = p.x * w; val y = p.y * h
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(x, y), Offset(x - p.size * 0.15f, y + p.size), strokeWidth = 1.2f)
                }
                if (lightningFlash > 0f) drawRect(Color.White.copy(alpha = 0.55f * lightningFlash))
            }
            ParticleKind.SNOW -> particles.forEach { p ->
                drawCircle(Color.White.copy(alpha = 0.85f), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.FOG -> particles.forEach { p ->
                val fw = p.size * w * 1.6f; val fh = h * 0.18f
                drawOval(Color.White.copy(alpha = 0.12f), topLeft = Offset(p.x * w - fw / 2, p.y * h - fh / 2), size = Size(fw, fh))
            }
            ParticleKind.NONE -> Unit
        }
    }
}
