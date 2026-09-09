package it.apexweather.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The reader's language and clock preference, resolved once near the top of the tree. Without a
 * default: a composable that formats a number must be under [ApexFormats], not silently fall back
 * to the JVM locale.
 */
val LocalFormats: ProvidableCompositionLocal<Formats> =
    compositionLocalOf { error("No Formats provided; wrap the content in ApexFormats") }

@Composable
fun rememberFormats(): Formats {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val locale: Locale = configuration.locales[0]
    val use24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, use24Hour) { Formats(locale, use24Hour) }
}
