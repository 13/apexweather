package it.apexweather.domain

import it.apexweather.domain.model.Condition

enum class ParticleKind { NONE, STARS, CLOUDS, RAIN, SNOW, FOG, LIGHTNING }

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

object SkyPaletteSelector {

    private fun p(top: Long, mid: Long, bottom: Long, accent: Long, ridge: Long, particle: ParticleKind, density: Float = 0f) =
        SkyPalette(top, mid, bottom, accent, ridge, particle, density)

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
                SunPhase.DAY -> p(0xFF2B5FA8, 0xFF5E93CF, 0xFFB8CFE6, 0xFFFFD166, 0xFF2F4468, ParticleKind.CLOUDS, 0.35f)
                SunPhase.DAWN, SunPhase.DUSK -> p(0xFF2A2650, 0xFF8A5C7A, 0xFFE4A07A, 0xFFFFB26B, 0xFF262640, ParticleKind.CLOUDS, 0.35f)
                SunPhase.NIGHT -> p(0xFF070B1E, 0xFF15213F, 0xFF2A3A5E, 0xFFDDE3F0, 0xFF0B1024, ParticleKind.STARS, 0.3f)
            }
            Condition.CLOUDY -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0B0F1C, 0xFF1C2333, 0xFF2E3648, 0xFFC9CFDB, 0xFF0D111C, ParticleKind.CLOUDS, 0.6f)
                else -> p(0xFF4A5568, 0xFF718096, 0xFFA0AEC0, 0xFFE2E8F0, 0xFF3A4352, ParticleKind.CLOUDS, 0.6f)
            }
            // Fog needs its own night, and had none: the daylight grey below is the palest palette
            // in the app, so a foggy night lit the whole screen up while every other condition went
            // dark. Nothing noticed because nothing renders fog here — no model has voted for it —
            // and until now no golden covered it either.
            Condition.FOG -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0D1017, 0xFF1B1F26, 0xFF2C3138, 0xFFD5D8DD, 0xFF0E1116, ParticleKind.FOG, 0.8f)
                else -> p(0xFF6B7280, 0xFF9CA3AF, 0xFFD1D5DB, 0xFFF3F4F6, 0xFF5B6270, ParticleKind.FOG, 0.8f)
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
