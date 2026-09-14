package it.apexweather.data

import android.graphics.BitmapFactory

/** A decoded tile: straight (not premultiplied) ARGB, row by row. */
class DecodedTile(val width: Int, val argb: IntArray)

/** PNG bytes to pixels. An interface so JVM tests can decode with ImageIO. */
fun interface TileDecoder {
    fun decode(bytes: ByteArray): DecodedTile?
}

/**
 * `inPremultiplied = false` is not optional: the radar's colours are matched exactly against a table
 * of straight-alpha values, and a premultiplied beige at alpha 110 matches nothing.
 */
object AndroidTileDecoder : TileDecoder {
    override fun decode(bytes: ByteArray): DecodedTile? {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return DecodedTile(bitmap.width, argb)
    }
}
