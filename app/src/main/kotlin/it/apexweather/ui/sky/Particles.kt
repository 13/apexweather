package it.apexweather.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import it.apexweather.domain.ParticleKind
import kotlin.math.sin
import kotlin.random.Random

/** One particle in normalized [0,1] space. */
class Particle(var x: Float, var y: Float, val size: Float, val speed: Float, val seed: Float)

class ParticleSystem(val kind: ParticleKind, density: Float, private val random: Random = Random(42)) {
    val particles: List<Particle>
    private var elapsed = 0f

    init {
        val count = when (kind) {
            ParticleKind.NONE -> 0
            ParticleKind.STARS -> (90 * density).toInt().coerceAtLeast(20)
            ParticleKind.RAIN -> (180 * density).toInt().coerceAtLeast(30)
            ParticleKind.SNOW -> (120 * density).toInt().coerceAtLeast(25)
        }
        particles = List(count) { newParticle(kind, random, spawnAnywhere = true) }
    }

    private fun newParticle(kind: ParticleKind, r: Random, spawnAnywhere: Boolean): Particle {
        val y = if (spawnAnywhere) r.nextFloat() else -0.05f
        return when (kind) {
            ParticleKind.STARS -> Particle(r.nextFloat(), r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 1.6f, 0f, r.nextFloat() * 6.28f)
            ParticleKind.RAIN -> Particle(r.nextFloat(), y, 8f + r.nextFloat() * 14f, 0.9f + r.nextFloat() * 0.6f, r.nextFloat())
            ParticleKind.SNOW -> Particle(r.nextFloat(), y, 1.5f + r.nextFloat() * 3f, 0.06f + r.nextFloat() * 0.08f, r.nextFloat() * 6.28f)
            ParticleKind.NONE -> Particle(0f, 0f, 0f, 0f, 0f)
        }
    }

    /** Advance by [dt] seconds. */
    fun step(dt: Float) {
        elapsed += dt
        for (p in particles) {
            when (kind) {
                ParticleKind.RAIN -> { p.y += p.speed * dt; p.x -= 0.08f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.SNOW -> { p.y += p.speed * dt; p.x += sin(elapsed * 0.8f + p.seed) * 0.02f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.STARS, ParticleKind.NONE -> Unit
            }
        }
    }

    fun draw(scope: DrawScope, accent: Color) = with(scope) {
        val w = size.width; val h = size.height
        when (kind) {
            ParticleKind.STARS -> particles.forEach { p ->
                val twinkle = 0.55f + 0.45f * sin(elapsed * 1.5f + p.seed)
                drawCircle(accent.copy(alpha = 0.9f * twinkle), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.RAIN -> particles.forEach { p ->
                val x = p.x * w; val y = p.y * h
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, y), Offset(x - p.size * 0.15f, y + p.size), strokeWidth = 1.2f)
            }
            ParticleKind.SNOW -> particles.forEach { p ->
                drawCircle(Color.White.copy(alpha = 0.85f), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.NONE -> Unit
        }
    }
}
