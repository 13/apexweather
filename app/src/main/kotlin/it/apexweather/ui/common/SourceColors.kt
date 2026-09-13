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
        Source.KNMI_HARMONIE -> Color(0xFF80CBC4)
        Source.DMI_HARMONIE -> Color(0xFFC5E1A5)
        Source.ECMWF -> Color(0xFFB39DDB)
        Source.ECMWF_AIFS -> Color(0xFFCE93D8)
        // The three globals that hold the far end of the list. Warm, so the chart's second week
        // does not read as more of the same violet the two ECMWF runs already own.
        Source.GFS -> Color(0xFFF2A65A)
        Source.UKMO -> Color(0xFFE08283)
        Source.GEM -> Color(0xFFD9C46A)
    }
    val consensus: Color = Color.White
}
