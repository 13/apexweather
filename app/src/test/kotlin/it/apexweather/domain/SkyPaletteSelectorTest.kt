package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPaletteSelectorTest {
    @Test fun `clear night has stars`() =
        assertEquals(ParticleKind.STARS, SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0).particle)

    @Test fun `rain has rain particles with density scaled by mm`() {
        val light = SkyPaletteSelector.select(Condition.RAIN, SunPhase.DAY, 0.5)
        val heavy = SkyPaletteSelector.select(Condition.HEAVY_RAIN, SunPhase.DAY, 8.0)
        assertEquals(ParticleKind.RAIN, light.particle)
        assertTrue(heavy.density > light.density)
        assertTrue(heavy.density <= 1f)
    }

    @Test fun `snow and thunderstorm map to their particle kinds`() {
        assertEquals(ParticleKind.SNOW, SkyPaletteSelector.select(Condition.SNOW, SunPhase.DAY, 1.0).particle)
        assertEquals(ParticleKind.LIGHTNING, SkyPaletteSelector.select(Condition.THUNDERSTORM, SunPhase.NIGHT, 3.0).particle)
        assertEquals(ParticleKind.FOG, SkyPaletteSelector.select(Condition.FOG, SunPhase.DAY, 0.0).particle)
    }

    @Test fun `phase changes clear palette`() {
        val day = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DAY, 0.0)
        val dusk = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DUSK, 0.0)
        assertNotEquals(day.top, dusk.top)
    }
}
