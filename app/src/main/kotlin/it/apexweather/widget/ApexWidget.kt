package it.apexweather.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import it.apexweather.MainActivity
import it.apexweather.R
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import it.apexweather.ui.common.Formats
import it.apexweather.ui.home.HomeStateBuilder
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): WeatherRepository
    fun settings(): SettingsRepository
    fun blender(): ConsensusBlender
    fun clock(): Clock
}

class ApexWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = ep.settings().settings.first()
        val snapshot = ep.repository().snapshot(settings.bulletinLanguage(Locale.getDefault().toLanguageTag())).first()
        val home = HomeStateBuilder.build(snapshot, settings, ep.blender().blend(snapshot.forecasts), ep.clock().instant())
        // The widget renders outside the composition, so it resolves the reader's language and
        // clock preference from its own context.
        val formats = Formats(
            context.resources.configuration.locales[0],
            android.text.format.DateFormat.is24HourFormat(context),
        )
        val state = WidgetStateBuilder.build(home, DorfTirol.ZONE, formats)
        val background = gradientBitmap(state.topColor, state.bottomColor)

        provideContent { WidgetContent(state, background) }
    }

    private fun gradientBitmap(top: Long, bottom: Long): Bitmap {
        val bmp = createBitmap(64, 128)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 128f, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 64f, 128f, paint)
        return bmp
    }

    companion object {
        val SMALL = androidx.compose.ui.unit.DpSize(110.dp, 50.dp)
        val MEDIUM = androidx.compose.ui.unit.DpSize(250.dp, 110.dp)
    }
}

@Composable
private fun WidgetContent(state: WidgetState, background: Bitmap) {
    val size = LocalSize.current
    val white = ColorProvider(Color.White)
    val isMedium = size.height >= ApexWidget.MEDIUM.height
    Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>())) {
        Image(ImageProvider(background), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
        Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(state.iconRes), contentDescription = null, modifier = GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text(state.tempText, style = TextStyle(color = white, fontSize = 30.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(DorfTirol.NAME, style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    // The small widget has room for two lines. While the data is current those are
                    // the place and the sky; once it goes stale the age takes the second line,
                    // because a small widget showing an old reading with nothing to say so is a lie.
                    if (isMedium || !state.isStale) {
                        Text(androidx.glance.LocalContext.current.getString(state.conditionRes), style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.8f)), fontSize = 12.sp))
                    }
                    if ((isMedium || state.isStale) && state.updatedText.isNotEmpty()) {
                        Text(
                            androidx.glance.LocalContext.current.getString(R.string.updated_at, state.updatedText),
                            style = TextStyle(color = ColorProvider(Color.White.copy(alpha = if (state.isStale) 0.95f else 0.7f)), fontSize = 11.sp),
                        )
                    }
                }
            }
            if (isMedium && state.hasData && state.hours.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    state.hours.forEach { h ->
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.label, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.75f)), fontSize = 11.sp))
                            Image(ImageProvider(h.iconRes), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                            Text(h.tempText, style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }
        // Drawn last so it sits on top of the content column; otherwise a tap in this corner falls
        // through to the whole-widget "open the app" click. Medium only: the small widget has no
        // corner free of the temperature.
        if (isMedium) {
            Box(GlanceModifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
                Image(
                    ImageProvider(R.drawable.ic_refresh),
                    contentDescription = androidx.glance.LocalContext.current.getString(R.string.refresh_now),
                    modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshWidgetAction>()),
                )
            }
        }
    }
}
