package it.apexweather

import javax.imageio.ImageIO

/** This morning's radar tiles, decoded the way Android's `BitmapFactory` would with straight alpha. */
object RadarFixtures {
    const val DIR = "map-2026-09-14"

    fun bytes(name: String): ByteArray =
        checkNotNull(RadarFixtures::class.java.getResourceAsStream("/fixtures/$DIR/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    fun decode(bytes: ByteArray): Pair<Int, IntArray> {
        val image = ImageIO.read(bytes.inputStream())
        return image.width to image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    fun tile(name: String): IntArray = decode(bytes(name)).second
}
