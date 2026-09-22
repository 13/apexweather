package it.apexweather.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Turning the drawn card into a file somebody else can open.
 *
 * The whole of this file is IO, so the whole of it is off the main thread. Every other piece of
 * per-emission work in this app is explicitly moved; a PNG encode of a 1080 px card is not the place
 * to make the exception.
 */
object ShareCapture {

    /** Where the pictures go. One directory, and it is the only one `share_paths.xml` exposes. */
    const val DIRECTORY = "share"

    /** How long a written picture is worth keeping. The share sheet copies what it needs. */
    val KEEP: Duration = Duration.ofHours(1)

    private const val AUTHORITY_SUFFIX = ".shares"

    /**
     * Writes [image] into the cache and returns a `content://` Uri for it.
     *
     * Old files are pruned on the way in rather than on a schedule: this runs whenever a share
     * happens and never otherwise, which is exactly when there is something to clean up. The app
     * should not accumulate pictures of last week's weather in a directory it has granted other
     * apps read access to.
     */
    suspend fun write(context: Context, image: ImageBitmap, now: Instant): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        prune(dir, now)
        val file = File(dir, "apexweather-${now.toEpochMilli()}.png")
        file.outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    /** Deletes anything in [dir] older than [KEEP]. Visible for the test that pins it. */
    fun prune(dir: File, now: Instant) {
        val cutoff = now.minus(KEEP).toEpochMilli()
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    /**
     * The chooser.
     *
     * [text] rides along as `EXTRA_TEXT` so a target that takes only text — an SMS app, a terminal
     * of a chat client that strips images — still receives the answer rather than nothing. The image
     * is the point; the sentence is the floor.
     */
    fun chooser(uri: Uri, text: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
