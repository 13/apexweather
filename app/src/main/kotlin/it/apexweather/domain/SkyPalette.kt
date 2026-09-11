package it.apexweather.domain

import it.apexweather.domain.model.Condition

/**
 * What falls or drifts across the sky. There is no CLOUDS any more: broken cloud used to draw a
 * handful of pale ovals sliding across the gradient, and they read as smudges on the screen rather
 * than as weather — distracting behind the text, and never informative, since the icon and the word
 * beside the temperature already say it is cloudy.
 */
enum class ParticleKind { NONE, STARS, RAIN, SNOW, FOG, LIGHTNING }

/** Colors are ARGB longs so this stays free of Android/Compose dependencies. */
data class SkyPalette(
    val top: Long,
    val mid: Long,
    val bottom: Long,
    val accent: Long,
    val ridge: Long,
    val particle: ParticleKind,
    val density: Float,
)

/**
 * How legible white text is on a given sky, and what to do about a sky that swallows it.
 *
 * Every word on the home screen is white and most of it is drawn straight onto the gradient, so a
 * light sky is a legibility bug rather than a matter of taste. On 2026-09-11 the dawn sky under
 * broken cloud ended at rgb(228, 160, 122), which carries white text at 2,2:1 where WCAG asks 4,5.
 */
object SkyContrast {
    /** WCAG AA for body text. The secondary lines are drawn at reduced alpha on top of this. */
    const val MIN_RATIO = 4.5

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Long): Double {
        val r = (argb shr 16 and 0xFF).toInt()
        val g = (argb shr 8 and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    fun withWhite(argb: Long): Double = 1.05 / (luminance(argb) + 0.05)

    /**
     * The same colour, darkened just enough to carry white text — and not a shade further, so a
     * sunrise stays a sunrise. Every channel is scaled by one factor, which is what keeps the hue:
     * the ratios between red, green and blue are untouched.
     */
    fun darkenForWhiteText(argb: Long): Long {
        if (withWhite(argb) >= MIN_RATIO) return argb
        val a = argb shr 24 and 0xFF
        val r = (argb shr 16 and 0xFF).toInt()
        val g = (argb shr 8 and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        // Luminance follows roughly the 2.2 power of the scale, so start from the closed form and
        // then walk down in small steps; the curve near black is not exactly a power law.
        val target = 1.05 / MIN_RATIO - 0.05
        var k = Math.pow(target / luminance(argb).coerceAtLeast(1e-6), 1 / 2.2)
        var out = scaled(a, r, g, b, k)
        while (withWhite(out) < MIN_RATIO && k > 0.02) {
            k -= 0.01
            out = scaled(a, r, g, b, k)
        }
        return out
    }

    private fun scaled(a: Long, r: Int, g: Int, b: Int, k: Double): Long {
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255).toLong()
        return (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

object SkyPaletteSelector {

    private fun p(top: Long, mid: Long, bottom: Long, accent: Long, ridge: Long, particle: ParticleKind, density: Float = 0f) =
        SkyPalette(
            // The gradient carries white text, so it is held to a contrast floor here rather than by
            // hand-picking each colour: a palette written later cannot then quietly go too light.
            SkyContrast.darkenForWhiteText(top),
            SkyContrast.darkenForWhiteText(mid),
            SkyContrast.darkenForWhiteText(bottom),
            accent, ridge, particle, density,
        )

    fun select(condition: Condition, phase: SunPhase, precipMm: Double): SkyPalette {
        val precipDensity = (0.25 + precipMm / 6.0).coerceIn(0.25, 1.0).toFloat()
        return when (condition) {
            Condition.CLEAR, Condition.MOSTLY_CLEAR -> when (phase) {
                SunPhase.DAY -> p(0xFF1E63C9, 0xFF4F9BE8, 0xFFA9D6F5, 0xFFFFD166, 0xFF2E4A7A, ParticleKind.NONE)
                SunPhase.DAWN -> p(0xFF1B2C5C, 0xFFD97A5A, 0xFFF6C177, 0xFFFFB26B, 0xFF2A2F4A, ParticleKind.NONE)
                SunPhase.DUSK -> p(0xFF221B4E, 0xFF9B3F7A, 0xFFF0895A, 0xFFFF9E6B, 0xFF1F1B3A, ParticleKind.NONE)
                SunPhase.NIGHT -> p(0xFF05081A, 0xFF0F1B3D, 0xFF1F3160, 0xFFE9EDF7, 0xFF0A0F24, ParticleKind.STARS, 0.6f)
            }
            Condition.PARTLY_CLOUDY -> when (phase) {
                SunPhase.DAY -> p(0xFF2B5FA8, 0xFF5E93CF, 0xFFB8CFE6, 0xFFFFD166, 0xFF2F4468, ParticleKind.NONE)
                SunPhase.DAWN, SunPhase.DUSK -> p(0xFF2A2650, 0xFF8A5C7A, 0xFFE4A07A, 0xFFFFB26B, 0xFF262640, ParticleKind.NONE)
                SunPhase.NIGHT -> p(0xFF070B1E, 0xFF15213F, 0xFF2A3A5E, 0xFFDDE3F0, 0xFF0B1024, ParticleKind.STARS, 0.3f)
            }
            Condition.CLOUDY -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0B0F1C, 0xFF1C2333, 0xFF2E3648, 0xFFC9CFDB, 0xFF0D111C, ParticleKind.NONE)
                else -> p(0xFF4A5568, 0xFF718096, 0xFFA0AEC0, 0xFFE2E8F0, 0xFF3A4352, ParticleKind.NONE)
            }
            // Fog needs its own night, and had none: the daylight grey below is the palest palette
            // in the app, so a foggy night lit the whole screen up while every other condition went
            // dark. Nothing noticed because nothing renders fog here — no model has voted for it —
            // and until now no golden covered it either.
            Condition.FOG -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0D1017, 0xFF1B1F26, 0xFF2C3138, 0xFFD5D8DD, 0xFF0E1116, ParticleKind.FOG, 0.8f)
                // Darkened to sit with the other daylight palettes. The original ran to #D1D5DB at
                // the foot of the screen, and this app writes in white: the moment fog became
                // reachable, the labels over it were barely there. Still the greyest sky in the
                // set, which is what makes it read as fog rather than as overcast.
                else -> p(0xFF4E5763, 0xFF6E7885, 0xFF97A1AD, 0xFFEDEFF2, 0xFF434B56, ParticleKind.FOG, 0.8f)
            }
            Condition.DRIZZLE, Condition.RAIN -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0A0E1A, 0xFF141C2E, 0xFF20304A, 0xFF8FB3E8, 0xFF0B101C, ParticleKind.RAIN, precipDensity)
                else -> p(0xFF2F3E55, 0xFF4B5D7A, 0xFF7C8FA8, 0xFFA9C8F5, 0xFF26334A, ParticleKind.RAIN, precipDensity)
            }
            Condition.HEAVY_RAIN -> p(0xFF141B29, 0xFF243247, 0xFF3A4C66, 0xFF8FB3E8, 0xFF10161F, ParticleKind.RAIN, precipDensity.coerceAtLeast(0.7f))
            Condition.SLEET -> p(0xFF2E3A4E, 0xFF4E5D74, 0xFF8494AA, 0xFFD6E4F5, 0xFF26303F, ParticleKind.SNOW, precipDensity)
            Condition.SNOW -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0E1526, 0xFF1F2B45, 0xFF3B4A68, 0xFFF1F5FF, 0xFF141C2E, ParticleKind.SNOW, precipDensity)
                else -> p(0xFF5B6B85, 0xFF8FA0BA, 0xFFD9E2EF, 0xFFFFFFFF, 0xFF4B5A73, ParticleKind.SNOW, precipDensity)
            }
            Condition.HEAVY_SNOW -> p(0xFF3D4A62, 0xFF6C7B95, 0xFFC5D0E0, 0xFFFFFFFF, 0xFF33405A, ParticleKind.SNOW, precipDensity.coerceAtLeast(0.7f))
            Condition.THUNDERSTORM -> p(0xFF0B0A1A, 0xFF1E1A3A, 0xFF2E2B52, 0xFFFFE28A, 0xFF0C0B1A, ParticleKind.LIGHTNING, precipDensity.coerceAtLeast(0.6f))
        }
    }
}
