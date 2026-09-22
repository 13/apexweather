package it.apexweather.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Writing the picture out, and not leaving it lying about.
 *
 * The provider reaches one cache directory and nothing else, so what has to be right here is that
 * the file lands in that directory, that it is a readable PNG, and that yesterday's pictures are
 * gone. A directory this app has granted other apps read access to is not somewhere to accumulate
 * last week's weather.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShareCaptureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val dir get() = File(context.cacheDir, ShareCapture.DIRECTORY)

    @Before
    fun clean() {
        dir.deleteRecursively()
    }

    private fun image(w: Int = 120, h: Int = 80) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2A6FD0.toInt()) }.asImageBitmap()

    /**
     * Everything that touches [ShareCapture.write] happens in this one test, and that is not
     * tidiness.
     *
     * `FileProvider` caches one `PathStrategy` per authority in a static map for the life of the
     * JVM, while Robolectric hands every test method its own data directory. A second test calling
     * `write` therefore resolves against the *first* test's cache root and fails with "Failed to
     * find configured root" — a failure about the test harness that reads exactly like a broken
     * `share_paths.xml`. One call, one place to look.
     */
    @Test
    fun `the picture is written, readable, and handed to a chooser that can open it`() = runTest {
        val uri = ShareCapture.write(context, image(), now)
        assertEquals("${context.packageName}.shares", uri.authority)

        val written = dir.listFiles()!!.single()
        val decoded = BitmapFactory.decodeFile(written.absolutePath)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)

        // The text rides along so a target that takes no image still receives the answer. The image
        // is the point; the sentence is the floor.
        val chooser = ShareCapture.chooser(uri, "Dorf Tirol · 18° · Wolkig", "Share")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals("Dorf Tirol · 18° · Wolkig", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `an hour-old picture is pruned and a fresh one is kept`() {
        dir.mkdirs()
        val old = File(dir, "apexweather-old.png").apply {
            writeBytes(ByteArray(4))
            setLastModified(now.minus(ShareCapture.KEEP).minusSeconds(60).toEpochMilli())
        }
        val fresh = File(dir, "apexweather-fresh.png").apply {
            writeBytes(ByteArray(4))
            setLastModified(now.minusSeconds(60).toEpochMilli())
        }

        ShareCapture.prune(dir, now)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `the plain-text form names the place, the temperature and the weather`() {
        assertEquals("Dorf Tirol · 18° · Wolkig", shareText("Dorf Tirol", "18°", "Wolkig"))
    }
}
