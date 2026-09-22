package it.apexweather.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant

/**
 * The path another app actually takes to the picture.
 *
 * `ShareCaptureTest` proves the file is written and the Uri is built; this proves the provider is
 * registered under the authority the code asks for and that a `ContentResolver` can open what it
 * points at. That is a manifest-and-resource agreement — `android:authorities`,
 * `@xml/share_paths` and `ShareCapture`'s suffix all have to say the same thing — and a
 * disagreement between them is invisible until a reader taps share.
 */
class ShareProviderDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSharedPictureCanBeOpenedThroughTheResolver() = runBlocking {
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF2A6FD0.toInt()) }
            .asImageBitmap()

        val uri = ShareCapture.write(context, image, Instant.now())
        assertEquals("${context.packageName}.shares", uri.authority)

        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        assertNotNull("the provider served nothing for its own Uri", decoded)
        assertEquals(120, decoded!!.width)
        assertEquals(80, decoded.height)
    }
}
