package it.apexweather.ui.common

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.model.Source

object SourceColors {
    fun of(source: Source): Color = when (source) {
        Source.SIAG_KMOS -> Color(0xFFFF8A65)
        Source.GEOSPHERE_AROME -> Color(0xFFEF5DA8)
        Source.ICON_CH1 -> Color(0xFF7CE0A5)
        Source.ICON_CH2 -> Color(0xFF3FBF8F)
        Source.ICON_2I -> Color(0xFF4FC3F7)
        Source.ICON_D2 -> Color(0xFFFFD166)
        Source.ECMWF -> Color(0xFFB39DDB)
    }
    val consensus: Color = Color.White
}
