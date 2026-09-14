# Map rain ribbon and nowcast radar check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the map drawing rain its own radar no longer sees, put forecast and radar on one colour scale, and replace the map's slider with a rain ribbon for the reader's place, with smooth playback.

**Architecture:** Phase 1 (Tasks 1–6) is the fix and ships alone: a generated copy of RainViewer's published colour table turns tile pixels into dBZ (`domain/RadarAtPlace`), `RadarRepository` reads the place's pixel in every frame, `NowcastRepository` asks for INCA when the next run is due, and `MapUiState.timeline` marks the first hour of forecast near the place `unconfirmed` when the newest radar frame is dry there. Phase 2 (Tasks 7–13) adds two zooms, a pure `RibbonModel`, the `RainRibbon` composable, a rewritten timeline card, crossfade and preload in `RadarMap`, and a smoothed `NowcastOverlay`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.08.00), osmdroid 6.1.20, Retrofit/OkHttp, Hilt, JUnit 4, Robolectric 4.16.1, Roborazzi 1.74.0, Python 3 for the generator.

**Spec:** `docs/superpowers/specs/2026-09-14-map-ribbon-and-nowcast-check-design.md`

## Global Constraints

- Build: `./gradlew :app:assembleDebug`; JVM tests `./gradlew :app:testDebugUnitTest`; device tests need `ANDROID_SERIAL=RZCXA1ZEXJE`; lint `./gradlew :app:lintDebug` must be clean (`warningsAsErrors`).
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android`; KSP only.
- `domain/` is pure Kotlin: no `android.*` imports there.
- Strings live in `values` (German, default), `values-it`, `values-en`; every new key in all three.
- Nothing that runs on a device may assert a German string; resolve from resources.
- Numbers and times go through `ui/common/Format.kt` with a `Formats`; never `Locale.ROOT`, never interpolate a number into a string.
- A Compose test rendering `SkyBackground` needs `rule.mainClock.autoAdvance = false`. The map screen does not render it.
- `MapViewModelTest` must keep `Dispatchers.setMain` and stop the loop in its `finally`.
- Roborazzi goldens: record with `./gradlew :app:recordRoborazziDebug`, open and look at every new PNG before committing.
- Radar below **15 dBZ** is not rain. Rates from dBZ use Marshall–Palmer `Z = 200·R^1.6`.
- Rain boundaries (mm/h): drawn from **0.1**, rain from **0.3**, moderate from **2.4**, heavy from **24**.
- Fix B window: **60 min** after the newest radar frame, **5 km** around the place.
- Fix A: publication lag seeded at **35 min**, never asks more often than every **3 min**.
- Crossfade **250 ms**; steps **350 ms** (Jetzt) and **450 ms** (Heute); hold **1200 ms** on "jetzt" and the last step.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21
  ```

## Fixture facts (measured 2026-09-14, used as expected values below)

All in `app/src/test/resources/fixtures/map-2026-09-14/`.

- Dorf Tirol (46.688958, 11.156624) is tile z7 x67 y45, pixel (247, 46).
- Every non-transparent pixel of all thirteen tiles is an exact Universal Blue table entry (81 067 pixels, 0 misses).
- Highest dBZ in the 3x3 patch at Dorf Tirol: 04:30Z **8**, 04:40Z **3**, 04:50Z–05:10Z no echo, 05:20Z **-10**, 05:30Z–06:30Z no echo.
- `inca-0500Z.json`, nearest cell (46.68674850463867, 11.16190242767334), rate in mm/h: 05:30Z 0.72, 05:45Z 0.96, 06:00Z 0.32, 06:15Z 0.12, 06:30Z 0.08, 06:45Z 0.2, 07:00Z on 0.0.

## File map

Create:
- `tools/radar-colors.py` — generator: CSV → `RadarColorTable.kt`
- `app/src/main/kotlin/it/apexweather/domain/RadarColorTable.kt` — generated ARGB tables
- `app/src/main/kotlin/it/apexweather/domain/RadarAtPlace.kt` — pixel → dBZ → rate
- `app/src/main/kotlin/it/apexweather/data/TileDecoder.kt` — PNG bytes → ARGB (interface + Android impl)
- `app/src/main/kotlin/it/apexweather/ui/map/RibbonModel.kt` — pure bars, words, labels
- `app/src/main/kotlin/it/apexweather/ui/map/RainRibbon.kt` — the composable
- `app/src/main/kotlin/it/apexweather/ui/map/FrameLayers.kt` — crossfade and preload for the MapView
- `app/src/test/kotlin/it/apexweather/RadarFixtures.kt` — ImageIO tile loader and JVM decoder for tests
- `app/src/test/kotlin/it/apexweather/domain/RadarAtPlaceTest.kt`
- `app/src/test/kotlin/it/apexweather/data/NowcastRepositoryTest.kt`
- `app/src/test/kotlin/it/apexweather/ui/map/RibbonModelTest.kt`
- `app/src/test/kotlin/it/apexweather/ui/screenshot/MapRibbonScreenshotTest.kt`

Modify:
- `app/src/main/kotlin/it/apexweather/ui/map/PrecipColors.kt`, `NowcastOverlay.kt`, `MapState.kt`, `MapViewModel.kt`, `MapScreen.kt`
- `app/src/main/kotlin/it/apexweather/data/RadarRepository.kt`, `NowcastRepository.kt`
- `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt`, `NowcastApi.kt`
- `app/src/main/kotlin/it/apexweather/di/AppModule.kt`
- `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`
- `app/src/main/res/values{,-it,-en}/strings.xml`
- Tests: `MapTimelineTest.kt`, `MapViewModelTest.kt`, `RadarRepositoryTest.kt`, `androidTest/.../MapContentTest.kt`
- `CLAUDE.md`

---

# Phase 1 — the fix

### Task 1: Radar colour table and `RadarAtPlace`

**Files:**
- Create: `tools/radar-colors.py`
- Create (generated): `app/src/main/kotlin/it/apexweather/domain/RadarColorTable.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/RadarAtPlace.kt`
- Create: `app/src/test/kotlin/it/apexweather/RadarFixtures.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/RadarAtPlaceTest.kt`

**Interfaces:**
- Produces: `RadarReading(dbz: Int?, snow: Boolean = false)` with `isRain`, `mmPerHour`, `RadarReading.NO_ECHO`; `TilePixel(zoom, x, y, px, py)`; `RadarAtPlace.pixelOf(lat, lon): TilePixel`, `RadarAtPlace.readingOf(argb: Int): RadarReading?` (null = colour not in table), `RadarAtPlace.read(argb: IntArray, width: Int, px: Int, py: Int): RadarReading`, `RadarAtPlace.rateOf(dbz: Int): Double`, constants `ZOOM = 7`, `TILE_SIZE = 256`, `RAIN_DBZ = 15`.
- Produces (tests): `RadarFixtures.tile(name: String): IntArray`, `RadarFixtures.bytes(name: String): ByteArray`.

- [ ] **Step 1: Write the generator**

`tools/radar-colors.py`:

```python
#!/usr/bin/env python3
"""Writes RadarColorTable.kt from RainViewer's published colour table.

RainViewer publishes the RGBA of every colour scheme at every whole dBZ
(https://www.rainviewer.com/files/rainviewer_api_colors_table.csv), in two blocks: rain, then snow.
The tiles this app receives are the "Universal Blue" column. Run from the repository root and
review the diff: a change here changes how every radar pixel is read.
"""
import csv
import pathlib
import sys

SRC = pathlib.Path("app/src/test/resources/fixtures/map-2026-09-14/rainviewer_api_colors_table.csv")
OUT = pathlib.Path("app/src/main/kotlin/it/apexweather/domain/RadarColorTable.kt")
COLUMN = "Universal Blue"
MIN_DBZ = -10


def main() -> None:
    rows = list(csv.reader(SRC.read_text().splitlines()))
    col = rows[0].index(COLUMN)
    blocks: list[dict[int, int]] = []
    for row in rows[1:]:
        if row[0] == "-32":
            blocks.append({})
        rgba = int(row[col].strip().lstrip("#"), 16)
        blocks[-1][int(row[0])] = ((rgba & 0xFF) << 24) | (rgba >> 8)
    if len(blocks) != 2:
        sys.exit(f"expected a rain and a snow block, found {len(blocks)}")

    def body(block: dict[int, int]) -> str:
        values = [block[d] for d in range(MIN_DBZ, max(block) + 1)]
        lines = [", ".join(f"0x{v:08X}.toInt()" for v in values[i:i + 4]) for i in range(0, len(values), 4)]
        return ",\n        ".join(lines)

    OUT.write_text(f"""package it.apexweather.domain

/**
 * RainViewer's "{COLUMN}" colours, as ARGB, one per whole dBZ from [MIN_DBZ] upward.
 *
 * Generated by `tools/radar-colors.py` from RainViewer's published table. Do not edit by hand.
 */
internal object RadarColorTable {{
    const val MIN_DBZ = {MIN_DBZ}

    val RAIN: IntArray = intArrayOf(
        {body(blocks[0])},
    )

    val SNOW: IntArray = intArrayOf(
        {body(blocks[1])},
    )
}}
""")
    print(f"wrote {OUT}: rain {max(blocks[0]) - MIN_DBZ + 1}, snow {max(blocks[1]) - MIN_DBZ + 1} entries")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run it**

Run: `python3 tools/radar-colors.py`
Expected: `wrote app/src/main/kotlin/it/apexweather/domain/RadarColorTable.kt: rain 106, snow 106 entries` (dBZ -10 to 95 in each block)

Open the file. `RAIN[25]` (dBZ 15) must be `0xFF88DDEE.toInt()` and `RAIN[16]` (dBZ 6) must be `0x6E9E9375.toInt()`.

- [ ] **Step 3: Write the test fixtures helper**

`app/src/test/kotlin/it/apexweather/RadarFixtures.kt`:

```kotlin
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
```

- [ ] **Step 4: Write the failing test**

`app/src/test/kotlin/it/apexweather/domain/RadarAtPlaceTest.kt`:

```kotlin
package it.apexweather.domain

import it.apexweather.Fixtures
import it.apexweather.RadarFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radar's word about one place, read off the tiles by RainViewer's own published table.
 *
 * The fixtures are the morning of 2026-09-14: a faint echo over Dorf Tirol at 04:30Z that no gauge
 * caught, gone by 05:30Z.
 */
class RadarAtPlaceTest {

    private val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540", "0550", "0600", "0610", "0620", "0630")

    private fun readingAt(time: String): RadarReading {
        val p = RadarAtPlace.pixelOf(DORF_TIROL.lat, DORF_TIROL.lon)
        return RadarAtPlace.read(RadarFixtures.tile("radar-z7-67-45-${time}Z.png"), RadarAtPlace.TILE_SIZE, p.px, p.py)
    }

    @Test
    fun `Dorf Tirol is pixel 247,46 of tile 67,45 at zoom 7`() {
        assertEquals(TilePixel(7, 67, 45, 247, 46), RadarAtPlace.pixelOf(DORF_TIROL.lat, DORF_TIROL.lon))
    }

    @Test
    fun `the generated table is RainViewer's Universal Blue column`() {
        val rows = Fixtures.read("${RadarFixtures.DIR}/rainviewer_api_colors_table.csv").lines().filter { it.isNotBlank() }
        val col = rows.first().split(",").indexOf("Universal Blue")
        var block = -1
        rows.drop(1).forEach { line ->
            val cells = line.split(",")
            val dbz = cells[0].toInt()
            if (dbz == -32) block++
            if (dbz < RadarColorTable.MIN_DBZ) return@forEach
            val rgba = cells[col].trim().removePrefix("#").toLong(16)
            val argb = (((rgba and 0xFF) shl 24) or (rgba ushr 8)).toInt()
            val table = if (block == 0) RadarColorTable.RAIN else RadarColorTable.SNOW
            assertEquals("block $block dBZ $dbz", argb, table[dbz - RadarColorTable.MIN_DBZ])
        }
    }

    @Test
    fun `this morning's echo was 8 dBZ, which is not rain`() {
        val r = readingAt("0430")
        assertEquals(8, r.dbz)
        assertFalse(r.isRain)
        assertFalse(r.snow)
    }

    @Test
    fun `by 05_30Z the radar saw nothing at all`() {
        assertEquals(RadarReading.NO_ECHO, readingAt("0530"))
        assertEquals(0.0, readingAt("0530").mmPerHour, 0.0)
    }

    @Test
    fun `the patch is the 3x3 around the place and takes its highest dBZ`() {
        assertEquals(listOf(8, 3, null, null, null, -10, null, null, null, null, null, null, null), times.map { readingAt(it).dbz })
    }

    /** A colour missing from the table would be read as no echo; RainViewer changing scheme must fail here. */
    @Test
    fun `every pixel of every recorded tile is in the table`() {
        times.forEach { t ->
            val unread = RadarFixtures.tile("radar-z7-67-45-${t}Z.png").count { RadarAtPlace.readingOf(it) == null }
            assertEquals("unreadable pixels at $t", 0, unread)
        }
    }

    @Test
    fun `a transparent pixel is no echo and an unknown colour is unreadable`() {
        assertEquals(RadarReading.NO_ECHO, RadarAtPlace.readingOf(0x00000000))
        assertNull(RadarAtPlace.readingOf(0xFF123456.toInt()))
    }

    @Test
    fun `the first rain colour is 15 dBZ, and a snow colour reads as snow`() {
        assertEquals(RadarReading(15), RadarAtPlace.readingOf(0xFF88DDEE.toInt()))
        assertTrue(RadarAtPlace.readingOf(0xFF88DDEE.toInt())!!.isRain)
        val snow20 = RadarColorTable.SNOW[20 - RadarColorTable.MIN_DBZ]
        assertEquals(RadarReading(20, snow = true), RadarAtPlace.readingOf(snow20))
    }

    @Test
    fun `Marshall-Palmer rates`() {
        assertEquals(0.32, RadarAtPlace.rateOf(15), 0.01)
        assertEquals(0.65, RadarAtPlace.rateOf(20), 0.01)
        assertEquals(23.7, RadarAtPlace.rateOf(45), 0.1)
    }
}
```

- [ ] **Step 5: Run it to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.RadarAtPlaceTest'`
Expected: compilation FAIL, `Unresolved reference: RadarAtPlace`.

- [ ] **Step 6: Implement**

`app/src/main/kotlin/it/apexweather/domain/RadarAtPlace.kt`:

```kotlin
package it.apexweather.domain

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.tan

/**
 * What the radar saw at one place, in reflectivity: [dbz] null means no echo at all.
 *
 * Below [RadarAtPlace.RAIN_DBZ] an echo is drawn (as a translucent beige) but is not rain: 14 dBZ is
 * under 0,3 mm/h, and on 2026-09-14 an 8 dBZ echo over Dorf Tirol reached no gauge.
 */
data class RadarReading(val dbz: Int?, val snow: Boolean = false) {
    val isRain: Boolean get() = dbz != null && dbz >= RadarAtPlace.RAIN_DBZ

    /** Marshall–Palmer, and zero for anything that is not rain. An approximation, not a gauge. */
    val mmPerHour: Double get() = if (isRain) RadarAtPlace.rateOf(dbz!!) else 0.0

    companion object {
        val NO_ECHO = RadarReading(null)
    }
}

/** A pixel of a Web Mercator tile. */
data class TilePixel(val zoom: Int, val x: Int, val y: Int, val px: Int, val py: Int)

/**
 * Reads RainViewer's tiles by the table RainViewer publishes, rather than by guessing at colours.
 *
 * Every non-transparent pixel of thirteen recorded tiles was an exact table entry, so there is no
 * nearest-colour fallback: a colour outside the table is unreadable and skipped, and a test fails if
 * any appear.
 */
object RadarAtPlace {
    const val ZOOM = 7
    const val TILE_SIZE = 256

    /** The first colour Universal Blue draws as rain rather than as a beige wash. */
    const val RAIN_DBZ = 15

    /** One pixel either side: `smooth` blurs edges, and a single pixel lands on a rim too easily. */
    private const val PATCH = 1

    // Rain first and lowest dBZ first, so a colour both blocks share reads as rain and a colour
    // repeated across dBZ reads as the least of them.
    private val lookup: Map<Int, RadarReading> = HashMap<Int, RadarReading>().apply {
        RadarColorTable.RAIN.forEachIndexed { i, argb ->
            if (argb ushr 24 != 0) putIfAbsent(argb, RadarReading(i + RadarColorTable.MIN_DBZ))
        }
        RadarColorTable.SNOW.forEachIndexed { i, argb ->
            if (argb ushr 24 != 0) putIfAbsent(argb, RadarReading(i + RadarColorTable.MIN_DBZ, snow = true))
        }
    }

    fun pixelOf(lat: Double, lon: Double): TilePixel {
        val n = 1 shl ZOOM
        val xf = (lon + 180.0) / 360.0 * n
        val yf = (1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        return TilePixel(
            ZOOM, x, y,
            ((xf - x) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1),
            ((yf - y) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1),
        )
    }

    /** Null when the colour is not in the table. */
    fun readingOf(argb: Int): RadarReading? =
        if (argb ushr 24 == 0) RadarReading.NO_ECHO else lookup[argb]

    /** The highest reading in the 3x3 patch around ([px], [py]). */
    fun read(argb: IntArray, width: Int, px: Int, py: Int): RadarReading {
        val height = argb.size / width
        var best = RadarReading.NO_ECHO
        for (dy in -PATCH..PATCH) {
            for (dx in -PATCH..PATCH) {
                val x = px + dx
                val y = py + dy
                if (x !in 0 until width || y !in 0 until height) continue
                val r = readingOf(argb[y * width + x]) ?: continue
                if ((r.dbz ?: Int.MIN_VALUE) > (best.dbz ?: Int.MIN_VALUE)) best = r
            }
        }
        return best
    }

    fun rateOf(dbz: Int): Double = (10.0.pow(dbz / 10.0) / 200.0).pow(1.0 / 1.6)
}
```

- [ ] **Step 7: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.RadarAtPlaceTest'`
Expected: PASS, 9 tests.

- [ ] **Step 8: Commit**

```bash
git add tools/radar-colors.py app/src/main/kotlin/it/apexweather/domain/RadarColorTable.kt app/src/main/kotlin/it/apexweather/domain/RadarAtPlace.kt app/src/test/kotlin/it/apexweather/RadarFixtures.kt app/src/test/kotlin/it/apexweather/domain/RadarAtPlaceTest.kt
git commit -m "feat: read radar tiles by RainViewer's published dBZ table"
```

---

### Task 2: `PrecipColors` on the radar's own scale

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/PrecipColors.kt` (whole file)
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt:51-60`
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt:65-66`
- Test: `app/src/test/kotlin/it/apexweather/ui/map/MapTimelineTest.kt` (class `PrecipColorsTest` at the bottom)

**Interfaces:**
- Consumes: `RadarAtPlace.rateOf`, `RadarColorTable` (Task 1).
- Produces: `PrecipColors.DRAWN_FROM_MM = 0.1`, `RAIN_FROM_MM = 0.3`, `MODERATE_FROM_MM = 2.4`, `HEAVY_FROM_MM = 24.0`, `PrecipColors.SUB_RAIN: Color`, `PrecipColors.forRate(mm): Color?` (null below 0.1, `SUB_RAIN` from 0.1 to 0.3), `PrecipColors.RAMP` (rain colours only), `PrecipColors.isRain(mm): Boolean`.

- [ ] **Step 1: Replace `PrecipColorsTest` with the failing version**

Add the imports `androidx.compose.ui.graphics.toArgb`, `it.apexweather.domain.RadarAtPlace` and `it.apexweather.domain.RadarColorTable` at the top of `MapTimelineTest.kt`, then replace the whole `class PrecipColorsTest { ... }` at the bottom with:

```kotlin
/** The colours both layers are drawn in: the radar's own, at the rates the radar means by them. */
class PrecipColorsTest {

    @Test
    fun `below a tenth of a millimetre nothing is drawn`() {
        assertNull(PrecipColors.forRate(0.0))
        assertNull(PrecipColors.forRate(0.09))
    }

    /** The radar draws 0-14 dBZ as a beige wash; forecast drizzle gets the same wash, not rain's blue. */
    @Test
    fun `under 0,3 mm per hour is the beige wash, not rain`() {
        assertEquals(PrecipColors.SUB_RAIN, PrecipColors.forRate(0.1))
        assertEquals(PrecipColors.SUB_RAIN, PrecipColors.forRate(0.29))
        assertFalse(PrecipColors.isRain(0.29))
        assertEquals(PrecipColors.RAMP.first(), PrecipColors.forRate(0.32))
    }

    /** Before this, orange meant 8 mm/h on the forecast and about 24 on the radar. */
    @Test
    fun `each colour is the radar's colour at the rate the radar means by it`() {
        listOf(15, 18, 20, 23, 29, 45, 50, 54).forEachIndexed { i, dbz ->
            val argb = RadarColorTable.RAIN[dbz - RadarColorTable.MIN_DBZ]
            val colour = PrecipColors.forRate(RadarAtPlace.rateOf(dbz) + 1e-9)
            assertEquals("dBZ $dbz", argb, colour!!.toArgb())
            assertEquals("stop $i", PrecipColors.RAMP[i], colour)
        }
    }

    @Test
    fun `heavier rain never picks a lighter colour`() {
        val seen = generateSequence(RadarAtPlace.rateOf(RadarAtPlace.RAIN_DBZ)) { it * 1.3 }.takeWhile { it < 200.0 }
            .mapNotNull { PrecipColors.forRate(it) }.toList()
        val indices = seen.map { PrecipColors.RAMP.indexOf(it) }
        assertEquals("the ramp must never step backwards", indices.sorted(), indices)
        assertTrue(indices.all { it >= 0 })
    }

    @Test
    fun `the heaviest rate there is still has a colour`() {
        assertEquals(PrecipColors.RAMP.last(), PrecipColors.forRate(500.0))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.PrecipColorsTest'`
Expected: compilation FAIL, `Unresolved reference: SUB_RAIN`.

- [ ] **Step 3: Rewrite `PrecipColors.kt`**

```kotlin
package it.apexweather.ui.map

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.RadarAtPlace

/**
 * The one intensity ramp the map speaks in, for the radar behind and the forecast in front.
 *
 * The radar tiles arrive in RainViewer's **Universal Blue** scheme, and RainViewer publishes what
 * every colour of it means in dBZ (see `RadarColorTable`). These stops are that scheme's colours at
 * 15, 18, 20, 23, 29, 45, 50 and 54 dBZ, and each is placed at the rate Marshall–Palmer gives for
 * its dBZ. They used to carry guessed rates — 0,1 mm/h for the first blue, 8 for orange — which put
 * the forecast about three times wetter than the radar at the same colour: on 2026-09-14 INCA's
 * drizzle over Dorf Tirol arrived painted like the radar's rain.
 *
 * Marshall–Palmer is the stratiform relation and an approximation, so the legend stays in words.
 */
object PrecipColors {

    /** Below this a forecast cell is not drawn at all. */
    const val DRAWN_FROM_MM = 0.1

    /** 15 dBZ: the first colour the radar draws as rain. */
    const val RAIN_FROM_MM = 0.3

    /** 29 dBZ. */
    const val MODERATE_FROM_MM = 2.4

    /** 45 dBZ, where the radar turns orange. */
    const val HEAVY_FROM_MM = 24.0

    /** The radar's 0-14 dBZ wash, at 10 dBZ: an echo, not rain. */
    val SUB_RAIN = Color(0x96CEC087)

    private val STOPS: List<Pair<Double, Color>> = listOf(
        15 to Color(0xFF88DDEE),
        18 to Color(0xFF36BAE5),
        20 to Color(0xFF00A3E0),
        23 to Color(0xFF0088BF),
        29 to Color(0xFF005B8E),
        45 to Color(0xFFFF4400),
        50 to Color(0xFFC10000),
        54 to Color(0xFF5D0000),
    ).map { (dbz, colour) -> RadarAtPlace.rateOf(dbz) to colour }

    /** What the legend draws, light to heavy. Rain only; the wash is not on it. */
    val RAMP: List<Color> = STOPS.map { it.second }

    fun isRain(mmPerHour: Double): Boolean = mmPerHour >= STOPS.first().first

    /** The colour for a rate in millimetres per hour: nothing below [DRAWN_FROM_MM], the wash below rain. */
    fun forRate(mmPerHour: Double): Color? = when {
        mmPerHour < DRAWN_FROM_MM -> null
        !isRain(mmPerHour) -> SUB_RAIN
        else -> STOPS.last { mmPerHour >= it.first }.second
    }
}
```

Note `RAIN_FROM_MM` (0.3) is the rounded boundary for words; `isRain` uses the exact 15 dBZ rate (0.3158). The test at 0.29/0.32 holds for both.

- [ ] **Step 4: Keep the wash translucent in `NowcastOverlay`**

In `NowcastOverlay.draw`, replace the line

```kotlin
            paint.color = colour.toArgb(if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10)
```

with

```kotlin
            // The wash carries its own alpha, as the radar's does; rain and "possible" take the overlay's.
            val strength = if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10
            paint.color = colour.toArgb((strength * colour.alpha).toInt())
```

- [ ] **Step 5: Correct the scheme comment in `RainViewerApi.kt`**

Replace

```kotlin
    /** RainViewer's colour scheme 4: blue for light rain through yellow and red for heavy. */
    const val COLOR_SCHEME = 4
```

with

```kotlin
    /**
     * Asked for as scheme 4, and answered in **Universal Blue** whatever is asked: every pixel of the
     * tiles recorded on 2026-09-14 is a Universal Blue entry in RainViewer's published table.
     * `RadarAtPlace` reads them by that table, and `RadarAtPlaceTest` fails if that stops being true.
     */
    const val COLOR_SCHEME = 4
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.*' --tests 'it.apexweather.domain.RadarAtPlaceTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/PrecipColors.kt app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt app/src/test/kotlin/it/apexweather/ui/map/MapTimelineTest.kt
git commit -m "fix: forecast rain is coloured at the rates the radar means by its colours"
```

---

### Task 3: Radar readings at the place in `RadarRepository`

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/TileDecoder.kt`
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt` (interface)
- Modify: `app/src/main/kotlin/it/apexweather/data/RadarRepository.kt` (whole file)
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt`
- Modify: `app/src/test/kotlin/it/apexweather/ui/map/MapViewModelTest.kt` (fake + constructor)
- Test: `app/src/test/kotlin/it/apexweather/data/RadarRepositoryTest.kt`

**Interfaces:**
- Consumes: `RadarAtPlace`, `RadarReading`, `RadarFixtures` (Task 1).
- Produces: `interface TileDecoder { fun decode(bytes: ByteArray): DecodedTile? }`, `data class DecodedTile(val width: Int, val argb: IntArray)`, `object AndroidTileDecoder : TileDecoder`; `RainViewerApi.tile(url: String): ResponseBody`; `RadarRepository(api, decoder, clock)` with `suspend fun readingsAt(lat: Double, lon: Double): Map<Instant, RadarReading>` (frames whose tile failed are absent).

- [ ] **Step 1: Add the tile call to `RainViewerApi`**

```kotlin
interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun weatherMaps(): RainViewerMaps

    /** One tile as PNG bytes, by its full URL from [RadarFrame.tileUrl]. */
    @GET
    suspend fun tile(@retrofit2.http.Url url: String): okhttp3.ResponseBody

    companion object { const val BASE_URL = "https://api.rainviewer.com/" }
}
```

- [ ] **Step 2: Create `TileDecoder.kt`**

```kotlin
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
```

- [ ] **Step 3: Provide it in `AppModule.kt`**

Beside the `rainViewer` provider add:

```kotlin
    @Provides @Singleton fun tileDecoder(): it.apexweather.data.TileDecoder = it.apexweather.data.AndroidTileDecoder
```

- [ ] **Step 4: Write the failing tests**

In `RadarRepositoryTest.kt`, replace `FakeApi` with one that also serves tiles, and pass a decoder everywhere a repository is built:

```kotlin
    private class FakeApi(var fail: Boolean = false) : RainViewerApi {
        var calls = 0
        override suspend fun weatherMaps(): RainViewerMaps {
            calls++
            if (fail) throw IOException("no network")
            return RainViewerMaps(
                host = "https://tilecache.rainviewer.com",
                radar = RainViewerRadar(past = listOf(RainViewerFrame(1789036800, "/v2/radar/5fa7198de455"))),
            )
        }
        override suspend fun tile(url: String): ResponseBody = throw IOException("no tiles in this fake")
    }

    private val decoder = TileDecoder { bytes -> RadarFixtures.decode(bytes).let { (w, px) -> DecodedTile(w, px) } }
```

Every existing `RadarRepository(api, clock)` in this file becomes `RadarRepository(api, decoder, clock)`.

Add, at the end of the class:

```kotlin
    /** This morning, 04:30Z to 05:40Z, as RainViewer listed it and as its tiles read at Dorf Tirol. */
    private class MorningApi : RainViewerApi {
        val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")
        var tileCalls = 0
        var failing: String? = null
        override suspend fun weatherMaps() = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(past = times.map {
                RainViewerFrame(Instant.parse("2026-09-14T${it.take(2)}:${it.drop(2)}:00Z").epochSecond, "/v2/radar/$it")
            }),
        )
        override suspend fun tile(url: String): ResponseBody {
            tileCalls++
            val time = url.substringAfter("/v2/radar/").take(4)
            check("/7/67/45/" in url) { "asked for the wrong tile: $url" }
            if (time == failing) throw IOException("dropped")
            return RadarFixtures.bytes("radar-z7-67-45-${time}Z.png").toResponseBody("image/png".toMediaType())
        }
    }

    private val lat = 46.688958
    private val lon = 11.156624

    @Test
    fun `readings at the place come from that place's own tile`() = runTest {
        val api = MorningApi()
        val readings = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z"))).readingsAt(lat, lon)
        assertEquals(8, readings[Instant.parse("2026-09-14T04:30:00Z")]?.dbz)
        assertEquals(RadarReading.NO_ECHO, readings[Instant.parse("2026-09-14T05:40:00Z")])
        assertEquals(8, readings.size)
    }

    @Test
    fun `a tile is fetched once per frame, not once per ask`() = runTest {
        val api = MorningApi()
        val repo = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z")))
        repo.readingsAt(lat, lon)
        repo.readingsAt(lat, lon)
        assertEquals(8, api.tileCalls)
    }

    /** A dropped tile is an unknown, not a dry frame: a missing reading must never overrule a forecast. */
    @Test
    fun `a failed tile leaves its frame out`() = runTest {
        val api = MorningApi().apply { failing = "0540" }
        val readings = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z"))).readingsAt(lat, lon)
        assertFalse(readings.containsKey(Instant.parse("2026-09-14T05:40:00Z")))
        assertEquals(7, readings.size)
    }
```

Imports to add: `it.apexweather.RadarFixtures`, `it.apexweather.domain.RadarReading`, `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.ResponseBody`, `okhttp3.ResponseBody.Companion.toResponseBody`, `org.junit.Assert.assertFalse`.

- [ ] **Step 5: Run to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.RadarRepositoryTest'`
Expected: compilation FAIL (`Too many arguments for public constructor RadarRepository`).

- [ ] **Step 6: Implement `RadarRepository.kt`**

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerMapper
import it.apexweather.domain.RadarAtPlace
import it.apexweather.domain.RadarReading
import it.apexweather.domain.TilePixel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radar frames currently on offer, held in memory for as long as they are current, and what
 * each of them saw at a place.
 *
 * Nothing here goes into Room. Every other upstream in this app is cached because the app renders
 * whatever it has while offline and says how old it is; radar frames are two hours of imagery whose
 * tiles are not stored either, so a cached frame list would name pictures that can no longer be
 * fetched.
 *
 * A failed fetch keeps whatever was already held and does not restart the clock, so the next time
 * the reader opens the map it tries again rather than waiting out the interval.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val api: RainViewerApi,
    private val decoder: TileDecoder,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var fetchedAt: Instant? = null

    /** Readings by frame and pixel. A frame is immutable, so a reading never goes stale, only old. */
    private val readings = HashMap<Pair<Instant, TilePixel>, RadarReading>()

    suspend fun frames(): List<RadarFrame> = mutex.withLock {
        val at = fetchedAt
        if (at != null && Duration.between(at, clock.instant()) < FRESH_FOR) return@withLock frames
        runCatchingCancellable { RainViewerMapper.map(api.weatherMaps()) }
            .onSuccess { frames = it; fetchedAt = clock.instant() }
        frames
    }

    /**
     * What every current frame saw at ([lat], [lon]), from the one zoom-7 tile that covers it.
     *
     * A frame whose tile could not be fetched or decoded is absent from the result rather than
     * reported dry: an unknown must never overrule a forecast.
     */
    suspend fun readingsAt(lat: Double, lon: Double): Map<Instant, RadarReading> {
        val current = frames()
        val pixel = RadarAtPlace.pixelOf(lat, lon)
        return mutex.withLock {
            val out = LinkedHashMap<Instant, RadarReading>()
            for (frame in current) {
                val key = frame.time to pixel
                val reading = readings[key] ?: runCatchingCancellable {
                    val bytes = api.tile(frame.tileUrl(pixel.zoom, pixel.x, pixel.y)).use { it.bytes() }
                    decoder.decode(bytes)?.let { RadarAtPlace.read(it.argb, it.width, pixel.px, pixel.py) }
                }.getOrNull()?.also { readings[key] = it }
                if (reading != null) out[frame.time] = reading
            }
            readings.keys.retainAll { (time, _) -> current.any { it.time == time } }
            out
        }
    }

    companion object {
        /** RainViewer publishes a new frame every ten minutes; asking sooner returns the same list. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
```

- [ ] **Step 7: Fix the other fake and constructor in `MapViewModelTest.kt`**

In `FakeRainViewer` add:

```kotlin
        override suspend fun tile(url: String): okhttp3.ResponseBody = throw java.io.IOException("no tiles in this test")
```

and change `RadarRepository(rainViewer, clock)` to `RadarRepository(rainViewer, { null }, clock)`.

- [ ] **Step 8: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.RadarRepositoryTest' --tests 'it.apexweather.ui.map.*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/TileDecoder.kt app/src/main/kotlin/it/apexweather/data/RadarRepository.kt app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt app/src/main/kotlin/it/apexweather/di/AppModule.kt app/src/test/kotlin/it/apexweather/data/RadarRepositoryTest.kt app/src/test/kotlin/it/apexweather/ui/map/MapViewModelTest.kt
git commit -m "feat: the radar repository reads what each frame saw at the place"
```

---

### Task 4: Fix A — ask for INCA when the next run is due

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/NowcastRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/NowcastRepositoryTest.kt` (create)

**Interfaces:**
- Produces: `NowcastRepository.SEED_LAG = 35 min`, `MIN_GAP = 3 min`, `RUN_STEP = 15 min`. `forPlace(place)` keeps its signature.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/it/apexweather/data/NowcastRepositoryTest.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.NowcastFeature
import it.apexweather.data.remote.NowcastGeometry
import it.apexweather.data.remote.NowcastParameter
import it.apexweather.data.remote.NowcastProperties
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.domain.DORF_TIROL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * On 2026-09-14 the map held INCA's 05:00Z run while the 05:15Z run that had dropped a dead shower
 * was already on offer, because it only asked every ten minutes. It now asks when a run is due.
 */
class NowcastRepositoryTest {

    private class FakeApi : NowcastApi {
        var reference: String = "2026-09-14T05:00+00:00"
        var calls = 0
        var fail = false
        override suspend fun precipitation(bbox: String, parameters: String, outputFormat: String): NowcastResponse {
            calls++
            if (fail) throw IOException("no network")
            return NowcastResponse(
                referenceTime = reference,
                timestamps = listOf(reference.replace(":00+", ":15+")),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rr" to NowcastParameter("kg m-2", listOf(0.1)))))),
            )
        }
        override suspend fun outlook(bbox: String, end: String, parameters: String, outputFormat: String) = NowcastResponse()
    }

    private val t = { hhmm: String -> Instant.parse("2026-09-14T$hhmm:00Z") }

    @Test
    fun `a held run is kept until the next one is due`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        assertEquals(1, api.calls)
        // 05:00 + 15 min + 35 min lag = 05:50.
        clock.now = t("05:45")
        repo.forPlace(DORF_TIROL)
        assertEquals("not due yet", 1, api.calls)
        clock.now = t("05:51")
        api.reference = "2026-09-14T05:15+00:00"
        repo.forPlace(DORF_TIROL)
        assertEquals("due", 2, api.calls)
    }

    /** A late run costs a request every three minutes, not one per resume. */
    @Test
    fun `a late run is asked for at most every three minutes`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        clock.now = t("05:52")
        repo.forPlace(DORF_TIROL) // due, but the service still has 05:00
        clock.now = t("05:53")
        repo.forPlace(DORF_TIROL)
        assertEquals(2, api.calls)
        clock.now = t("05:55")
        repo.forPlace(DORF_TIROL)
        assertEquals(3, api.calls)
    }

    /** Only a smaller lag is believed: a fetch can land long after a run appeared, never before. */
    @Test
    fun `the lag learns from a run seen sooner`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:20")) // 05:00 run seen after 20 min
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        clock.now = t("05:36") // 05:00 + 15 + 20 = 05:35, due
        repo.forPlace(DORF_TIROL)
        assertEquals(2, api.calls)
    }

    @Test
    fun `a new place is asked for at once`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        repo.forPlace(DORF_TIROL.copy(istat = "021008", lat = 46.5, lon = 11.3))
        assertEquals(2, api.calls)
    }

    @Test
    fun `a failed fetch keeps what was held`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        val held = repo.forPlace(DORF_TIROL)
        api.fail = true
        clock.now = t("05:51")
        assertEquals(held, repo.forPlace(DORF_TIROL))
    }
}
```

`MutableClock` already exists in `it.apexweather.data` test sources (used by `MapViewModelTest`).

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.NowcastRepositoryTest'`
Expected: FAIL on `a late run is asked for at most every three minutes` and `the lag learns from a run seen sooner` (the ten-minute rule asks at different moments).

- [ ] **Step 3: Implement**

In `NowcastRepository.kt` replace the fields and `forPlace` and the companion:

```kotlin
    private val mutex = Mutex()
    private var istat: String? = null
    private var nowcast: PrecipNowcast = PrecipNowcast.EMPTY
    private var askedAt: Instant? = null

    /** How long after its reference time a run appears. Only ever lowered; see [forPlace]. */
    private var lag: Duration = SEED_LAG

    suspend fun forPlace(place: Place): PrecipNowcast = mutex.withLock {
        val now = clock.instant()
        if (istat == place.istat && !due(now)) return@withLock nowcast
        askedAt = now
        val box = NowcastApi.boxAround(place)
        // The two halves are fetched independently and either is worth having on its own: INCA
        // carries the next two and a half hours at a kilometre and a quarter hour, AROME the rest of
        // the day at 2,5 km and an hour. A failure in one leaves the other's stretch of the timeline
        // standing.
        // An answer with no steps is no answer: the mappers return EMPTY for a response without a
        // reference time, and treating that as a fetch would overwrite a good held run with nothing.
        val near = runCatchingCancellable { NowcastMapper.map(api.precipitation(box)) }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
        val far = runCatchingCancellable {
            NowcastMapper.mapOutlook(
                api.outlook(box, NowcastApi.endOf(now)),
                after = near?.steps?.lastOrNull()?.time,
            )
        }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
        val fetched = when {
            near == null && far == null -> null
            else -> PrecipNowcast(
                issuedAt = near?.issuedAt ?: far!!.issuedAt,
                steps = near?.steps.orEmpty() + far?.steps.orEmpty(),
            )
        }
        if (fetched != null) {
            // A fetch can land long after a run appeared but never before it, so only a shorter
            // delay than the one held is evidence.
            if (near != null && near.issuedAt.isAfter(nowcast.issuedAt)) {
                val seen = Duration.between(near.issuedAt, now)
                if (seen < lag) lag = seen
            }
            nowcast = fetched
            istat = place.istat
        } else if (istat != place.istat) {
            // The held forecast is about somewhere else, and somewhere else's rain is worse than none.
            nowcast = PrecipNowcast.EMPTY
            istat = null
        }
        nowcast
    }

    /**
     * Whether a newer INCA run should be on offer: the held run's reference time, plus the fifteen
     * minutes to the next run, plus how late runs appear. Never more often than [MIN_GAP].
     */
    private fun due(now: Instant): Boolean {
        val asked = askedAt ?: return true
        if (Duration.between(asked, now) < MIN_GAP) return false
        if (nowcast.steps.isEmpty()) return true
        return !now.isBefore(nowcast.issuedAt.plus(RUN_STEP).plus(lag))
    }

    companion object {
        /** INCA's cadence. */
        val RUN_STEP: Duration = Duration.ofMinutes(15)

        /** Measured 2026-09-14: the 05:00Z run was on offer at 05:36Z and the 05:15Z one by 05:49Z. */
        val SEED_LAG: Duration = Duration.ofMinutes(35)

        /** The floor under all of it, so a late run costs a request every three minutes. */
        val MIN_GAP: Duration = Duration.ofMinutes(3)
    }
```

Remove the `fetchedAt` field and `FRESH_FOR`. Update the class doc's last paragraph to: "A failed fetch keeps whatever was held; the next ask after [MIN_GAP] tries again." Then `grep -rn "NowcastRepository.FRESH_FOR" app/src` must print nothing.

Check the first test's lag: the 05:00 run seen at 05:36 gives 36 min, which is not below the 35 min seed, so due stays 05:50.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.NowcastRepositoryTest' --tests 'it.apexweather.ui.map.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/NowcastRepository.kt app/src/test/kotlin/it/apexweather/data/NowcastRepositoryTest.kt
git commit -m "fix: the map asks for INCA when its next run is due, not every ten minutes"
```

---

### Task 5: Fix B — the newest radar frame overrules the first hour near the place

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/NowcastApi.kt` (`NowcastCell`)
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt` (`refresh`)
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt:55-56`
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt` (`Timeline`, `FrameKindChip`)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Test: `app/src/test/kotlin/it/apexweather/ui/map/MapTimelineTest.kt`, `app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt`

**Interfaces:**
- Consumes: `RadarReading`, `RadarAtPlace` (Task 1); `RadarRepository.readingsAt` (Task 3).
- Produces: `NowcastCell.unconfirmed: Boolean = false`; `data class PlaceCheck(val lat: Double, val lon: Double, val readings: Map<Instant, RadarReading>)`; `MapUiState.timeline(radar, forecast, check: PlaceCheck? = null)`; `MapUiState.check: PlaceCheck?`, `MapUiState.unconfirmedHere: Boolean`, `MapUiState.radarDrySince: Instant?`; constants `MapUiState.CHECK_WINDOW = 60 min`, `CHECK_RADIUS_KM = 5.0`; strings `map_kind_unconfirmed`, `map_radar_sees_nothing`.

- [ ] **Step 1: Write the failing tests**

Add the imports `it.apexweather.Fixtures`, `it.apexweather.RadarFixtures`, `it.apexweather.data.remote.NowcastMapper`, `it.apexweather.data.remote.NowcastResponse`, `it.apexweather.domain.RadarAtPlace` and `it.apexweather.domain.RadarReading` to `MapTimelineTest.kt` (a fully qualified `it.apexweather…` inside a lambda would resolve `it` as the lambda's parameter and not compile). Then append inside `class MapTimelineTest`:

```kotlin
    // --- Fix B: the radar overrules the first hour of forecast near the place -----------------

    private val lat = 46.688958
    private val lon = 11.156624

    private fun instantOf(hhmm: String): Instant = Instant.parse("2026-09-14T${hhmm.take(2)}:${hhmm.drop(2)}:00Z")

    private fun morningRadar(vararg hhmm: String) = hhmm.map { RadarFrame(instantOf(it), "https://example.invalid/$it") }

    private fun morningReadings(vararg hhmm: String): Map<Instant, RadarReading> {
        val p = RadarAtPlace.pixelOf(lat, lon)
        return hhmm.associate { t -> instantOf(t) to RadarAtPlace.read(RadarFixtures.tile("radar-z7-67-45-${t}Z.png"), 256, p.px, p.py) }
    }

    private val inca05 by lazy {
        NowcastMapper.map(Fixtures.json.decodeFromString(NowcastResponse.serializer(), Fixtures.read("map-2026-09-14/inca-0500Z.json")))
    }

    private val times = arrayOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")

    private fun List<MapFrame>.step(hhmm: String) =
        filterIsInstance<MapFrame.Forecast>().first { it.time == Instant.parse("2026-09-14T$hhmm:00Z") }.step

    private fun NowcastStep.atPlace() = cells.first { it.lat == 46.68674850463867 && it.lon == 11.16190242767334 }

    /**
     * 2026-09-14, as the reader saw it at 07:45 local: the newest radar frame (05:40Z) dry over Dorf
     * Tirol, and INCA's 05:00Z run still carrying a shower that had died an hour before. Nobody's
     * gauge caught a drop.
     */
    @Test
    fun `this morning's dead shower is marked unconfirmed at Dorf Tirol`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        assertTrue(frames.step("05:45").atPlace().unconfirmed)
        assertEquals(0.96, frames.step("05:45").atPlace().mmPerHour, 0.001)
        assertTrue(frames.step("06:00").atPlace().unconfirmed)
        assertTrue(frames.step("06:15").atPlace().unconfirmed)
    }

    @Test
    fun `past an hour after the newest radar frame nothing is marked`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        assertTrue(frames.step("06:45").cells.isNotEmpty())
        assertFalse(frames.step("06:45").cells.any { it.unconfirmed })
    }

    @Test
    fun `cells further than five kilometres are never marked`() {
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, morningReadings(*times)))
        val far = frames.step("05:45").cells.filter { kotlin.math.abs(it.lat - lat) > 0.06 }
        assertTrue("the fixture must have rain far away for this to prove anything", far.isNotEmpty())
        assertFalse(far.any { it.unconfirmed })
    }

    @Test
    fun `where the newest frame is raining at the place nothing is marked`() {
        val wet = morningReadings(*times).mapValues { RadarReading(30) }
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, wet))
        assertFalse(frames.filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
    }

    /** A tile that failed is an unknown, and an unknown overrules nothing. */
    @Test
    fun `with no reading for the newest frame nothing is marked`() {
        val readings = morningReadings(*times) - Instant.parse("2026-09-14T05:40:00Z")
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, PlaceCheck(lat, lon, readings))
        assertFalse(frames.filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
        assertFalse(MapUiState.timeline(morningRadar(*times), inca05.steps, null)
            .filterIsInstance<MapFrame.Forecast>().any { f -> f.step.cells.any { it.unconfirmed } })
    }

    @Test
    fun `the card knows since when the radar has seen no rain here`() {
        val check = PlaceCheck(lat, lon, morningReadings(*times))
        val frames = MapUiState.timeline(morningRadar(*times), inca05.steps, check)
        val state = MapUiState(frames = frames, selected = frames.indexOfFirst { it.time == Instant.parse("2026-09-14T05:45:00Z") }, check = check, loading = false)
        assertTrue(state.unconfirmedHere)
        // 04:30Z's 8 dBZ is an echo but not rain, so the dry run reaches back to the first frame.
        assertEquals(Instant.parse("2026-09-14T04:30:00Z"), state.radarDrySince)
    }
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.MapTimelineTest'`
Expected: compilation FAIL, `Unresolved reference: PlaceCheck`.

- [ ] **Step 3: Add the field to `NowcastCell`**

```kotlin
data class NowcastCell(
    val lat: Double,
    val lon: Double,
    val mmPerHour: Double,
    val upperMmPerHour: Double? = null,
    /**
     * The newest radar frame is dry at the place and this cell is within the first hour and a few
     * kilometres of it: drawn as "possible", never as rain. See `MapUiState.timeline`.
     */
    val unconfirmed: Boolean = false,
)
```

- [ ] **Step 4: Implement in `MapState.kt`**

Add after the `MapFrame` interface:

```kotlin
/** What the radar saw at the place, frame by frame. A frame whose tile failed is absent. */
data class PlaceCheck(val lat: Double, val lon: Double, val readings: Map<Instant, RadarReading>)
```

Add to `MapUiState`'s constructor, after `loading`:

```kotlin
    /** Null until the place and its readings are known; nothing is marked without it. */
    val check: PlaceCheck? = null,
```

Add to the body of `MapUiState`:

```kotlin
    /** The frame on screen is a forecast whose rain near the place the radar does not see. */
    val unconfirmedHere: Boolean
        get() = (frame as? MapFrame.Forecast)?.step?.cells?.any { it.unconfirmed } == true

    /** The oldest frame of the unbroken run of rainless readings up to the newest one. */
    val radarDrySince: Instant?
        get() {
            val c = check ?: return null
            return frames.filterIsInstance<MapFrame.Observed>()
                .takeLastWhile { c.readings[it.time]?.isRain == false }
                .firstOrNull()?.time
        }
```

Replace `timeline` in the companion with:

```kotlin
        /** How long after the newest radar frame its word about the place still counts. */
        val CHECK_WINDOW: Duration = Duration.ofMinutes(60)

        /** How far around the place the radar's word reaches: where the pixels were read. */
        const val CHECK_RADIUS_KM = 5.0

        /**
         * Merges the radar's past with the nowcast's future into one timeline.
         *
         * Forecast steps at or before the newest radar image are dropped rather than shown: INCA is
         * issued on the quarter hour and reaches back to cover the gap to its own reference time, so
         * its first step or two often describe minutes a radar has already watched. Where both
         * exist the radar is the better witness, and a timeline that ran backwards through them
         * would be nonsense.
         *
         * **And the radar may overrule the forecast's first hour near the place.** On 2026-09-14 the
         * newest frame was dry over Dorf Tirol while INCA's run, built while an echo still counted,
         * put 0,96 mm/h there fifteen minutes later — and no gauge in the valley caught a drop. Where
         * [check] has a rainless reading for the newest frame, forecast cells within
         * [CHECK_RADIUS_KM] and [CHECK_WINDOW] are marked `unconfirmed`. Past the window INCA may be
         * right about rain arriving from outside the patch, so nothing is marked there; and with no
         * reading for the newest frame nothing is marked at all.
         */
        fun timeline(radar: List<RadarFrame>, forecast: List<NowcastStep>, check: PlaceCheck? = null): List<MapFrame> {
            val observed = radar.sortedBy { it.time }.map(MapFrame::Observed)
            val lastSeen = observed.lastOrNull()?.time
            val dryAtPlace = lastSeen != null && check?.readings?.get(lastSeen)?.isRain == false
            val ahead = forecast
                .filter { step -> lastSeen == null || step.time.isAfter(lastSeen) }
                .sortedBy { it.time }
                .map { step ->
                    if (!dryAtPlace || Duration.between(lastSeen, step.time) > CHECK_WINDOW) step
                    else step.copy(cells = step.cells.map { cell ->
                        if (distanceKm(cell.lat, cell.lon, check!!.lat, check.lon) <= CHECK_RADIUS_KM) cell.copy(unconfirmed = true) else cell
                    })
                }
                .map(MapFrame::Forecast)
            return observed + ahead
        }

        private fun distanceKm(lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
            val dy = (lat - lat0) * KM_PER_DEGREE
            val dx = (lon - lon0) * KM_PER_DEGREE * cos(Math.toRadians(lat0))
            return sqrt(dx * dx + dy * dy)
        }

        private const val KM_PER_DEGREE = 111.2
```

Imports: `it.apexweather.domain.RadarReading`, `java.time.Duration`, `kotlin.math.cos`, `kotlin.math.sqrt`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.MapTimelineTest'`
Expected: PASS.

- [ ] **Step 6: Feed the check from `MapViewModel.refresh`**

```kotlin
    fun refresh() {
        viewModelScope.launch {
            val past = radar.frames()
            val place = _state.value.place
            // The forecast is only asked for once a place is known, because the box it covers is
            // drawn around the place. A failure here leaves the radar loop intact: the map was worth
            // looking at without a forecast until now, and still is.
            val ahead = place?.let { nowcast.forPlace(it).steps }.orEmpty()
            val check = place?.let { PlaceCheck(it.lat, it.lon, radar.readingsAt(it.lat, it.lon)) }
            val frames = MapUiState.timeline(past, ahead, check)
            _state.update { state ->
                state.copy(frames = frames, check = check, selected = state.selectionAfter(frames), loading = false)
            }
        }
    }
```

- [ ] **Step 7: Draw unconfirmed cells as "possible" in `NowcastOverlay`**

Replace

```kotlin
            val solid = PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null else cell.upperMmPerHour?.let(PrecipColors::forRate)
```

with

```kotlin
            // A cell the radar does not confirm is only ever "possible", however wet INCA calls it.
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
```

- [ ] **Step 8: Strings**

`values/strings.xml`, after `map_kind_outlook`:

```xml
    <string name="map_kind_unconfirmed">unsicher</string>
    <string name="map_radar_sees_nothing">Radar sieht hier seit %1$s keinen Regen</string>
```

`values-it/strings.xml`:

```xml
    <string name="map_kind_unconfirmed">incerto</string>
    <string name="map_radar_sees_nothing">Il radar qui non vede pioggia dalle %1$s</string>
```

`values-en/strings.xml`:

```xml
    <string name="map_kind_unconfirmed">uncertain</string>
    <string name="map_radar_sees_nothing">Radar has seen no rain here since %1$s</string>
```

- [ ] **Step 9: Show it on the card in `MapScreen.kt`**

In `FrameKindChip`, change the signature to `FrameKindChip(frame: MapFrame?, unconfirmed: Boolean)` and the label selection to:

```kotlin
    val label = when {
        unconfirmed -> R.string.map_kind_unconfirmed
        frame is MapFrame.Forecast && frame.step.kind == NowcastKind.OUTLOOK -> R.string.map_kind_outlook
        frame is MapFrame.Forecast -> R.string.map_kind_forecast
        else -> R.string.map_kind_radar
    }
    val forecast = frame is MapFrame.Forecast
    val colour = when {
        unconfirmed -> Color.White.copy(alpha = 0.7f)
        forecast -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF9CC9FF)
    }
```

In `Timeline`, pass `FrameKindChip(state.frame, state.unconfirmedHere)`, and directly after the header `Row { ... }` add:

```kotlin
        val drySince = state.radarDrySince
        if (state.unconfirmedHere && drySince != null) {
            Text(
                stringResource(R.string.map_radar_sees_nothing, Format.time(drySince, SouthTyrol.ZONE, formats)),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.testTag("map_radar_overrule"),
            )
        }
```

- [ ] **Step 10: Device test**

Add the import `it.apexweather.domain.RadarReading`, then append to `MapContentTest`:

```kotlin
    /** The radar's word overrules the forecast's first hour near the place, and the card says so in a word. */
    @Test
    fun anUnconfirmedStepIsLabelledUncertainAndSaysWhy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val times = frames.map { it.time }
        val check = PlaceCheck(place.lat, place.lon, times.associateWith { RadarReading.NO_ECHO })
        val state = MapUiState(
            frames = MapUiState.timeline(frames, steps, check), check = check,
            selected = 13, playing = false, place = place, loading = false,
        )
        rule.setContent { ApexTheme { MapContent(state, onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithText(context.getString(R.string.map_kind_unconfirmed)).assertIsDisplayed()
        rule.onNodeWithTag("map_radar_overrule").assertIsDisplayed()
    }
```

(The fixture's `steps` sit at 46.68, 11.15, 0.7 km from `place`, and index 13 is the first forecast step, 15 minutes after the newest frame.)

- [ ] **Step 11: Run everything**

Run: `./gradlew :app:testDebugUnitTest` then `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.map.MapContentTest`
Expected: both PASS.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/remote/NowcastApi.kt app/src/main/kotlin/it/apexweather/ui/map/MapState.kt app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml app/src/test/kotlin/it/apexweather/ui/map/MapTimelineTest.kt app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt
git commit -m "fix: the newest radar frame overrules the forecast's first hour near the place"
```

---

### Task 6: Phase 1 verification, docs and release

**Files:**
- Modify: `CLAUDE.md` (the `ui/map/` bullet)
- Modify: `app/build.gradle.kts` (default version, after the release, the way `8d2ac99` did it)

- [ ] **Step 1: Update `CLAUDE.md`**

In the `ui/map/` bullet, replace the sentence beginning "**Both layers share one colour ramp** (`PrecipColors`), read off live RainViewer tiles, and the legend is labelled light-to-heavy rather than in millimetres: RainViewer does not publish what its scheme 4 colours mean in rate, and the app will not invent numbers for somebody else's scale." with:

```markdown
  **Both layers share one colour ramp** (`PrecipColors`), and **RainViewer does publish what its
  colours mean**: `rainviewer_api_colors_table.csv` gives every scheme's RGBA at every dBZ, rain
  and snow. The tiles arrive in **Universal Blue** whatever scheme the URL asks for, every pixel of
  thirteen recorded tiles is an exact entry, and `tools/radar-colors.py` generates
  `RadarColorTable.kt` from it. `PrecipColors`' stops sit at the Marshall–Palmer rate of their dBZ;
  they used to carry guessed rates that painted the forecast three times wetter than the radar.
  Below 15 dBZ the radar's beige wash is not rain. The legend stays in words, because
  Marshall–Palmer is an approximation.
  **The radar overrules the forecast's first hour near the place** (`MapUiState.timeline`,
  `PlaceCheck`). On 2026-09-14 INCA's 05:00Z run carried a shower the radar had lost an hour before
  over Dorf Tirol, the map drew rain at 08:00, the home screen said 0,0 mm and the ground stayed dry.
  Where the newest frame reads no rain at the place (`RadarRepository.readingsAt`), cells within
  5 km and 60 min are `unconfirmed` and drawn as "possible". A tile that failed is an unknown and
  overrules nothing. The home screen deliberately does not read INCA: that morning it was the one
  that was wrong. `NowcastRepository` asks for a run when it is due (reference + 15 min + a lag
  seeded at 35 min and only ever lowered), never more often than every 3 min.
```

- [ ] **Step 2: Full verification**

Run each and read the output:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:verifyRoborazziDebug
./gradlew :app:lintDebug
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease && ./tools/release-smoke.sh RZCXA1ZEXJE
```

Expected: all green; the smoke script reports no crash, `ClassNotFoundException` or `SerializationException`.

- [ ] **Step 3: On the phone**

```bash
./gradlew :app:assembleDebug && adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Open Karte. Check: radar and forecast colours look continuous across "jetzt"; forecast drizzle is beige, not blue; with dry radar at the place and INCA rain within the hour, the chip reads "unsicher" and the line names a time. Say in the commit or release notes which of these the weather that day allowed you to see.

- [ ] **Step 4: Commit docs**

```bash
git add CLAUDE.md
git commit -m "docs: the radar's published scale and its word over the nowcast"
```

- [ ] **Step 5: Release**

Ask the user before tagging. Then:

```bash
git tag v0.22.0
git push origin main v0.22.0
gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')" --exit-status
gh release view v0.22.0 --json assets -q '.assets[].name'
```

Expected: `ApexWeather-0.22.0.apk`. Then bump the default version in `app/build.gradle.kts` to `0.22.0` exactly as `git show 8d2ac99` does, commit as `chore: default version follows the v0.22.0 release`, push.

---

# Phase 2 — the ribbon

### Task 7: Two zooms in the state

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/NowcastApi.kt` (`PrecipNowcast`)
- Modify: `app/src/main/kotlin/it/apexweather/data/NowcastRepository.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt`
- Test: `MapTimelineTest.kt`, `MapViewModelTest.kt`, `NowcastRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 5's `timeline`, `PlaceCheck`.
- Produces: `PrecipNowcast.outlook: List<NowcastStep> = emptyList()` (every AROME hour, not trimmed at INCA's end); `enum class MapZoom { NOW, TODAY }`; `MapUiState.outlook: List<MapFrame>`, `zoom: MapZoom`, `visible: List<MapFrame>`, `presentTime: Instant?`, `fun withZoom(zoom): MapUiState`; `MapUiState.NOW_BEHIND = 2 h`, `NOW_AHEAD = 3 h`, `TODAY_AHEAD = 24 h`; `MapViewModel.setZoom(zoom)`.

- [ ] **Step 1: Failing tests**

Add the import `it.apexweather.data.remote.NowcastKind`, then append to `MapTimelineTest`:

```kotlin
    // --- Zooms -----------------------------------------------------------------------------

    private fun outlook(vararg hours: Long) = hours.map { h ->
        MapFrame.Forecast(NowcastStep(t0.plusSeconds(h * 3600), listOf(NowcastCell(46.6, 11.1, 1.0)), NowcastKind.OUTLOOK))
    }

    @Test
    fun `Jetzt reaches three hours past the newest radar frame and no further`() {
        val frames = MapUiState.timeline(radar(-10, 0), steps(15, 180, 195))
        assertEquals(t0.plusSeconds(180 * 60), frames.last().time)
    }

    @Test
    fun `the zoom decides which frames are visible`() {
        val s = MapUiState(frames = MapUiState.timeline(radar(-10, 0), steps(15)), outlook = outlook(1, 2, 3), loading = false)
        assertEquals(3, s.visible.size)
        assertEquals(3, s.withZoom(MapZoom.TODAY).visible.size)
        assertTrue(s.withZoom(MapZoom.TODAY).visible.all { it is MapFrame.Forecast })
    }

    @Test
    fun `switching zoom keeps the instant when the other zoom has it`() {
        val s = MapUiState(
            frames = MapUiState.timeline(radar(-10, 0), steps(15, 30, 45, 60)),
            outlook = outlook(1, 2, 3), selected = 5, loading = false,
        )
        assertEquals(t0.plusSeconds(3600), s.frame?.time)
        val today = s.withZoom(MapZoom.TODAY)
        assertEquals(t0.plusSeconds(3600), today.frame?.time)
    }

    @Test
    fun `switching zoom lands on the present when the other zoom does not have the instant`() {
        val s = MapUiState(
            frames = MapUiState.timeline(radar(-20, -10, 0), steps(15)),
            outlook = outlook(1, 2, 3), selected = 0, loading = false,
        )
        assertEquals(0, s.withZoom(MapZoom.TODAY).selected)
        assertFalse(s.withZoom(MapZoom.TODAY).playing)
        assertEquals(2, s.withZoom(MapZoom.TODAY).withZoom(MapZoom.NOW).selected)
    }
```

Append to `MapViewModelTest`:

```kotlin
    @Test
    fun `switching zoom stops the loop`() = mapTest { vm ->
        vm.play()
        advanceTimeBy(1_000)
        vm.setZoom(MapZoom.TODAY)
        runCurrent()
        assertFalse(vm.state.value.playing)
        assertEquals(MapZoom.TODAY, vm.state.value.zoom)
    }
```

Append to `NowcastRepositoryTest`:

```kotlin
    /** Heute needs every AROME hour, including the ones INCA's finer steps already cover in Jetzt. */
    @Test
    fun `the outlook keeps every hour`() = runTest {
        val api = object : NowcastApi {
            override suspend fun precipitation(bbox: String, parameters: String, outputFormat: String) = NowcastResponse(
                referenceTime = "2026-09-14T05:00+00:00",
                timestamps = listOf("2026-09-14T06:00+00:00"),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rr" to NowcastParameter("", listOf(0.1)))))),
            )
            override suspend fun outlook(bbox: String, end: String, parameters: String, outputFormat: String) = NowcastResponse(
                referenceTime = "2026-09-14T00:00+00:00",
                timestamps = listOf("2026-09-14T06:00+00:00", "2026-09-14T07:00+00:00"),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rain_p50" to NowcastParameter("", listOf(1.0, 1.0)))))),
            )
        }
        val result = NowcastRepository(api, MutableClock(t("05:36"))).forPlace(DORF_TIROL)
        assertEquals(2, result.outlook.size)
        assertEquals(2, result.steps.size) // INCA 06:00, then AROME 07:00 only
    }
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.*' --tests 'it.apexweather.data.NowcastRepositoryTest'`
Expected: compilation FAIL, `Unresolved reference: MapZoom`.

- [ ] **Step 3: `PrecipNowcast.outlook` and the repository**

In `NowcastApi.kt`:

```kotlin
data class PrecipNowcast(
    val issuedAt: Instant,
    val steps: List<NowcastStep>,
    /** Every hour of AROME's ensemble, for the Heute zoom; [steps] drops the ones INCA covers. */
    val outlook: List<NowcastStep> = emptyList(),
) {
    companion object { val EMPTY = PrecipNowcast(Instant.EPOCH, emptyList()) }
}
```

In `NowcastRepository.forPlace`, replace the `far` and `fetched` blocks with:

```kotlin
        val far = runCatchingCancellable { NowcastMapper.mapOutlook(api.outlook(box, NowcastApi.endOf(now)), after = null) }.getOrNull()
            ?.takeIf { it.steps.isNotEmpty() }
        val nearEnd = near?.steps?.lastOrNull()?.time
        val fetched = when {
            near == null && far == null -> null
            else -> PrecipNowcast(
                issuedAt = near?.issuedAt ?: far!!.issuedAt,
                steps = near?.steps.orEmpty() + far?.steps.orEmpty().filter { nearEnd == null || it.time.isAfter(nearEnd) },
                outlook = far?.steps.orEmpty(),
            )
        }
```

- [ ] **Step 4: `MapState.kt`**

Add:

```kotlin
/** How much of the day the ribbon spans. */
enum class MapZoom {
    /** Two hours of radar and three of forecast, a quarter hour a step. */
    NOW,

    /** The next day, an hour a step, from AROME's ensemble alone. */
    TODAY,
}
```

In `MapUiState`'s constructor, after `frames`:

```kotlin
    /** The Heute zoom's frames: AROME hours from the present to a day ahead. */
    val outlook: List<MapFrame> = emptyList(),
    val zoom: MapZoom = MapZoom.NOW,
```

Change `frame` and `nowIndex` and add the helpers:

```kotlin
    /** The frames the current zoom scrubs and plays through. */
    val visible: List<MapFrame> get() = if (zoom == MapZoom.NOW) frames else outlook

    val frame: MapFrame? get() = visible.getOrNull(selected)

    /** Where the past ends in the visible frames; in Heute the present is its first hour. */
    val nowIndex: Int get() = if (zoom == MapZoom.NOW) frames.indexOfLast { it is MapFrame.Observed } else 0

    /** The newest instant a radar saw, which is what every "in 35 min" is measured from. */
    val presentTime: Instant? get() = frames.lastOrNull { it is MapFrame.Observed }?.time ?: visible.firstOrNull()?.time

    /** The same state in [zoom], on the same instant if that zoom has it and on the present otherwise. */
    fun withZoom(zoom: MapZoom): MapUiState {
        if (zoom == this.zoom) return this
        val at = frame?.time
        val next = copy(zoom = zoom, playing = false)
        val list = next.visible
        if (list.isEmpty()) return next.copy(selected = 0)
        val inside = at != null && !at.isBefore(list.first().time) && !at.isAfter(list.last().time)
        val index = if (inside) list.indices.minBy { abs(list[it].time.epochSecond - at!!.epochSecond) }
            else next.nowIndex.coerceIn(0, list.lastIndex)
        return next.copy(selected = index)
    }
```

In `selectionAfter`, keep the signature (it receives the list the new state will show). In `timeline`, after `.sortedBy { it.time }` add the horizon:

```kotlin
                .filter { step ->
                    // Comparable, not Duration.isPositive: that is Java 18 and minSdk is 31.
                    val from = lastSeen ?: forecast.minOf { it.time }
                    Duration.between(from, step.time) <= NOW_AHEAD
                }
```

and to the companion:

```kotlin
        val NOW_AHEAD: Duration = Duration.ofHours(3)
        val TODAY_AHEAD: Duration = Duration.ofHours(24)
```

- [ ] **Step 5: `MapViewModel.kt`**

In `refresh`, build the outlook and select within the visible list:

```kotlin
            val held = place?.let { nowcast.forPlace(it) }
            val ahead = held?.steps.orEmpty()
            val check = place?.let { PlaceCheck(it.lat, it.lon, radar.readingsAt(it.lat, it.lon)) }
            val frames = MapUiState.timeline(past, ahead, check)
            val present = past.lastOrNull()?.time ?: ahead.firstOrNull()?.time
            val outlook = held?.outlook.orEmpty()
                .filter { present == null || (!it.time.isBefore(present.truncatedTo(java.time.temporal.ChronoUnit.HOURS)) && !it.time.isAfter(present.plus(MapUiState.TODAY_AHEAD))) }
                .map(MapFrame::Forecast)
            _state.update { state ->
                val next = state.copy(frames = frames, outlook = outlook, check = check)
                next.copy(selected = state.selectionAfter(next.visible), loading = false)
            }
```

(Remove the old `val ahead = ...` line.) `selectionAfter` reads `frame` from `state`, which now resolves through `visible`, so an instant is carried across in either zoom.

Replace `select`, and the frame lists inside `play`:

```kotlin
    fun select(index: Int) {
        pause()
        _state.update { it.copy(selected = index.coerceIn(0, (it.visible.size - 1).coerceAtLeast(0))) }
    }

    fun setZoom(zoom: MapZoom) {
        pause()
        _state.update { it.withZoom(zoom) }
    }
```

In `play()`, replace every `frames` read with `visible` (`_state.value.visible.size < 2`, `it.selected >= it.visible.lastIndex`, `s.visible.size < 2`, `s.selected >= s.visible.lastIndex`), hold on "jetzt" as well as the end, and use the zoom's step:

```kotlin
                val s0 = _state.value
                val hold = s0.selected >= s0.visible.lastIndex || s0.selected == s0.nowIndex
                delay(if (hold) LOOP_PAUSE_MS else if (s0.zoom == MapZoom.NOW) NOW_FRAME_MS else TODAY_FRAME_MS)
```

Companion: replace `FRAME_MS = 400L` with `NOW_FRAME_MS = 350L` and `TODAY_FRAME_MS = 450L`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.*' --tests 'it.apexweather.data.NowcastRepositoryTest'`
Expected: PASS, including every pre-existing `MapViewModelTest` test.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/remote/NowcastApi.kt app/src/main/kotlin/it/apexweather/data/NowcastRepository.kt app/src/main/kotlin/it/apexweather/ui/map/MapState.kt app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt app/src/test/kotlin/it/apexweather/ui/map/MapTimelineTest.kt app/src/test/kotlin/it/apexweather/ui/map/MapViewModelTest.kt app/src/test/kotlin/it/apexweather/data/NowcastRepositoryTest.kt
git commit -m "feat: the map timeline has a Jetzt and a Heute zoom"
```

---

### Task 8: `RibbonModel` and `Format.shortDuration`

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/map/RibbonModel.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/map/RibbonModelTest.kt`

**Interfaces:**
- Consumes: `MapUiState` (Task 7), `PrecipColors` constants (Task 2), `PrecipScale.fillFraction`.
- Produces: `enum class BarKind { OBSERVED, NOWCAST, OUTLOOK }`; `data class RibbonBar(val time: Instant, val kind: BarKind, val mmPerHour: Double, val upperMmPerHour: Double?, val unconfirmed: Boolean)`; `enum class RainWord { DRY, POSSIBLE, LIGHT, MODERATE, HEAVY }`; `RibbonModel.bars(state: MapUiState): List<RibbonBar>`; `RibbonModel.word(bar: RibbonBar): RainWord`; `RibbonModel.fraction(mm: Double): Float`; `RibbonModel.labelIndices(bars: List<RibbonBar>, zoom: MapZoom): List<Int>`; `Format.shortDuration(d: Duration, f: Formats): String`.

- [ ] **Step 1: Failing test**

`app/src/test/kotlin/it/apexweather/ui/map/RibbonModelTest.kt`:

```kotlin
package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastCell
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.RadarReading
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

class RibbonModelTest {

    private val t0 = Instant.parse("2026-09-14T06:00:00Z")
    private val place = DORF_TIROL

    private fun state(): MapUiState {
        val radar = listOf(RadarFrame(t0.minusSeconds(600), "x"), RadarFrame(t0, "y"))
        val here = NowcastCell(place.lat + 0.002, place.lon, 0.96)
        val away = NowcastCell(place.lat + 0.1, place.lon, 9.0)
        val steps = listOf(
            NowcastStep(t0.plusSeconds(900), listOf(here, away)),
            NowcastStep(t0.plusSeconds(1800), listOf(away)),
            NowcastStep(t0.plusSeconds(2700), listOf(here.copy(mmPerHour = 0.15))),
            NowcastStep(t0.plusSeconds(3600), listOf(here.copy(mmPerHour = 30.0))),
        )
        val check = PlaceCheck(place.lat, place.lon, mapOf(t0.minusSeconds(600) to RadarReading(30), t0 to RadarReading(8)))
        return MapUiState(frames = MapUiState.timeline(radar, steps, check), check = check, place = place, loading = false)
    }

    @Test
    fun `a bar per visible frame, with the place's own value`() {
        val bars = RibbonModel.bars(state())
        assertEquals(listOf(BarKind.OBSERVED, BarKind.OBSERVED, BarKind.NOWCAST, BarKind.NOWCAST, BarKind.NOWCAST, BarKind.NOWCAST), bars.map { it.kind })
        assertEquals(2.73, bars[0].mmPerHour, 0.01) // 30 dBZ
        assertEquals(0.0, bars[1].mmPerHour, 0.0) // 8 dBZ is not rain
        assertEquals(0.96, bars[2].mmPerHour, 0.0)
        assertEquals("rain five kilometres away is not rain here", 0.0, bars[3].mmPerHour, 0.0)
    }

    @Test
    fun `words follow the radar's boundaries`() {
        val bars = RibbonModel.bars(state())
        assertEquals(RainWord.MODERATE, RibbonModel.word(bars[0]))
        assertEquals(RainWord.DRY, RibbonModel.word(bars[1]))
        // The newest frame is dry at the place, so the first hour is only possible.
        assertEquals(RainWord.POSSIBLE, RibbonModel.word(bars[2]))
        assertEquals(RainWord.DRY, RibbonModel.word(bars[3]))
        assertEquals(RainWord.POSSIBLE, RibbonModel.word(bars[4]))
    }

    @Test
    fun `word boundaries`() {
        fun w(mm: Double, upper: Double? = null, unconfirmed: Boolean = false) =
            RibbonModel.word(RibbonBar(t0, BarKind.OUTLOOK, mm, upper, unconfirmed))
        assertEquals(RainWord.DRY, w(0.0))
        assertEquals(RainWord.POSSIBLE, w(0.1))
        assertEquals(RainWord.POSSIBLE, w(0.0, upper = 0.5))
        assertEquals(RainWord.LIGHT, w(0.33))
        assertEquals(RainWord.MODERATE, w(2.4))
        assertEquals(RainWord.HEAVY, w(24.0))
        assertEquals(RainWord.POSSIBLE, w(24.0, unconfirmed = true))
    }

    @Test
    fun `Heute bars come from the outlook`() {
        val outlook = listOf(MapFrame.Forecast(NowcastStep(t0, listOf(NowcastCell(place.lat, place.lon, 1.0, 3.0)), NowcastKind.OUTLOOK)))
        val bars = RibbonModel.bars(state().copy(outlook = outlook).withZoom(MapZoom.TODAY))
        assertEquals(1, bars.size)
        assertEquals(BarKind.OUTLOOK, bars[0].kind)
        assertEquals(3.0, bars[0].upperMmPerHour!!, 0.0)
    }

    /** Local hours: t0 is 06:00Z, 08:00 in the province in September. */
    @Test
    fun `labels fall on whole local hours, every hour in Jetzt and at 00, 06, 12, 18 in Heute`() {
        val quarter = (0 until 20).map { RibbonBar(t0.plusSeconds(it * 900L), BarKind.NOWCAST, 0.0, null, false) }
        assertEquals(listOf(0, 4, 8, 12, 16), RibbonModel.labelIndices(quarter, MapZoom.NOW))
        val hourly = (0 until 24).map { RibbonBar(t0.plusSeconds(it * 3600L), BarKind.OUTLOOK, 0.0, null, false) }
        assertEquals(listOf(4, 10, 16, 22), RibbonModel.labelIndices(hourly, MapZoom.TODAY))
    }

    @Test
    fun `short durations`() {
        val f = Formats(Locale.GERMAN, use24Hour = true)
        assertEquals("35 min", Format.shortDuration(Duration.ofMinutes(35), f))
        assertEquals("9 h", Format.shortDuration(Duration.ofMinutes(9 * 60 + 10), f))
        assertEquals("20 min", Format.shortDuration(Duration.ofMinutes(-20), f))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.RibbonModelTest'`
Expected: compilation FAIL, `Unresolved reference: RibbonModel`.

- [ ] **Step 3: `Format.shortDuration`**

In `Format.kt`, beside `dayTime`:

```kotlin
    /** "35 min" or "9 h", unsigned: the words around it say whether it is ahead or behind. */
    fun shortDuration(d: Duration, f: Formats): String {
        val minutes = d.abs().toMinutes()
        return if (minutes < 60) "${f.whole(minutes.toInt())} min" else "${f.whole(((minutes + 30) / 60).toInt())} h"
    }
```

Import `java.time.Duration` if absent.

- [ ] **Step 4: `RibbonModel.kt`**

```kotlin
package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastKind
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.home.PrecipScale
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

enum class BarKind { OBSERVED, NOWCAST, OUTLOOK }

/** One step of the ribbon, about the reader's place alone. */
data class RibbonBar(
    val time: Instant,
    val kind: BarKind,
    val mmPerHour: Double,
    val upperMmPerHour: Double?,
    val unconfirmed: Boolean,
)

enum class RainWord { DRY, POSSIBLE, LIGHT, MODERATE, HEAVY }

/**
 * What the ribbon draws, worked out without a screen.
 *
 * A bar is the place's own value at that step: what the radar saw there, or the forecast grid cell
 * nearest to it. The radar's word is only rain from 15 dBZ; the forecast's cell is only "here"
 * within three quarters of its own grid spacing, so rain one cell over is not rain here.
 */
object RibbonModel {

    private const val NOWCAST_REACH_KM = 0.75
    private const val OUTLOOK_REACH_KM = 1.9

    fun bars(state: MapUiState): List<RibbonBar> {
        val lat = state.place?.lat ?: state.check?.lat
        val lon = state.place?.lon ?: state.check?.lon
        return state.visible.map { frame ->
            when (frame) {
                is MapFrame.Observed -> RibbonBar(
                    frame.time, BarKind.OBSERVED,
                    state.check?.readings?.get(frame.time)?.mmPerHour ?: 0.0, null, false,
                )
                is MapFrame.Forecast -> {
                    val outlook = frame.step.kind == NowcastKind.OUTLOOK
                    val reach = if (outlook) OUTLOOK_REACH_KM else NOWCAST_REACH_KM
                    val cell = if (lat == null || lon == null) null else frame.step.cells
                        .map { it to distanceKm(it.lat, it.lon, lat, lon) }
                        .filter { it.second <= reach }
                        .minByOrNull { it.second }?.first
                    RibbonBar(
                        frame.time, if (outlook) BarKind.OUTLOOK else BarKind.NOWCAST,
                        cell?.mmPerHour ?: 0.0, cell?.upperMmPerHour, cell?.unconfirmed ?: false,
                    )
                }
            }
        }
    }

    fun word(bar: RibbonBar): RainWord = when {
        bar.unconfirmed && bar.mmPerHour >= PrecipColors.DRAWN_FROM_MM -> RainWord.POSSIBLE
        bar.mmPerHour >= PrecipColors.HEAVY_FROM_MM -> RainWord.HEAVY
        bar.mmPerHour >= PrecipColors.MODERATE_FROM_MM -> RainWord.MODERATE
        PrecipColors.isRain(bar.mmPerHour) -> RainWord.LIGHT
        bar.mmPerHour >= PrecipColors.DRAWN_FROM_MM -> RainWord.POSSIBLE
        (bar.upperMmPerHour ?: 0.0) >= PrecipColors.RAIN_FROM_MM -> RainWord.POSSIBLE
        else -> RainWord.DRY
    }

    /** The same square-root scale as the hour strip, so a height means the same rain on both screens. */
    fun fraction(mm: Double): Float = PrecipScale.fillFraction(mm)

    /** Bars that carry an hour label: whole local hours, every hour in Jetzt and 00/06/12/18 in Heute. */
    fun labelIndices(bars: List<RibbonBar>, zoom: MapZoom): List<Int> {
        val every = if (zoom == MapZoom.NOW) 1 else 6
        return bars.indices.filter { i ->
            val local = bars[i].time.atZone(SouthTyrol.ZONE)
            local.minute == 0 && local.second == 0 && local.hour % every == 0
        }
    }

    private fun distanceKm(lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
        val dy = (lat - lat0) * 111.2
        val dx = (lon - lon0) * 111.2 * cos(Math.toRadians(lat0))
        return sqrt(dx * dx + dy * dy)
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.map.RibbonModelTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/RibbonModel.kt app/src/main/kotlin/it/apexweather/ui/common/Format.kt app/src/test/kotlin/it/apexweather/ui/map/RibbonModelTest.kt
git commit -m "feat: a pure model of the map's rain ribbon"
```

---

### Task 9: `RainRibbon` composable and its goldens

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/map/RainRibbon.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/screenshot/MapRibbonScreenshotTest.kt`

**Interfaces:**
- Consumes: `RibbonBar`, `BarKind`, `RibbonModel.fraction` (Task 8).
- Produces: `@Composable fun RainRibbon(bars: List<RibbonBar>, selected: Int, nowIndex: Int, labels: List<Pair<Int, String>>, stateDescription: String, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)`; test tag `map_ribbon`; colours `RibbonColors.OBSERVED`, `RibbonColors.FORECAST`.

- [ ] **Step 1: Implement the composable**

```kotlin
package it.apexweather.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

object RibbonColors {
    /** The radar's own mid blue, 18 dBZ: what was seen. */
    val OBSERVED = Color(0xFF36BAE5)

    /** Forecast chrome only. Never a rain colour: the map's rain is always [PrecipColors]. */
    val FORECAST = Color(0xFFFFC861)
}

/**
 * The map's timeline as rain at the reader's place: a bar per step, so the answer to "when does it
 * reach me" is on screen before anything plays.
 *
 * Solid blue is what the radar saw, amber hatching what a model expects, a dashed outline what a
 * model expects and the radar does not confirm. It behaves as a slider for accessibility, and
 * releasing a drag within a step of "jetzt" snaps to it.
 */
@Composable
fun RainRibbon(
    bars: List<RibbonBar>,
    selected: Int,
    nowIndex: Int,
    labels: List<Pair<Int, String>>,
    stateDescription: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // The pointer handlers are installed once, so everything they read has to be the latest value.
    val currentSelected by rememberUpdatedState(selected)
    val currentBars by rememberUpdatedState(bars)
    val currentNow by rememberUpdatedState(nowIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val fontScale = LocalDensity.current.fontScale
    val barAreaHeight = (34 * fontScale).dp

    fun indexAt(x: Float, width: Int): Int =
        if (currentBars.isEmpty()) 0 else (x / width * currentBars.size).toInt().coerceIn(0, currentBars.lastIndex)

    Column(
        modifier
            .fillMaxWidth()
            .testTag("map_ribbon")
            .semantics(mergeDescendants = true) {
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    selected.toFloat(), 0f..(bars.size - 1).coerceAtLeast(1).toFloat(), steps = (bars.size - 2).coerceAtLeast(0),
                )
                setProgress { value -> onSelect(value.roundToInt().coerceIn(0, (bars.size - 1).coerceAtLeast(0))); true }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(barAreaHeight)
                .pointerInput(Unit) {
                    detectTapGestures { currentOnSelect(indexAt(it.x, size.width)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (currentNow >= 0 && abs(currentSelected - currentNow) <= 1) currentOnSelect(currentNow) },
                    ) { change, _ ->
                        val i = indexAt(change.position.x, size.width)
                        if (i != currentSelected) {
                            if (currentBars[i].time.epochSecond % 3600 == 0L) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(i)
                        }
                    }
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val pitch = size.width / bars.size
            val barWidth = pitch * 0.72f
            val stub = 2.dp.toPx()
            val radius = CornerRadius(2.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val left = i * pitch + (pitch - barWidth) / 2f
                val h = if (bar.mmPerHour < PrecipColors.DRAWN_FROM_MM) stub else (RibbonModel.fraction(bar.mmPerHour) * size.height).coerceAtLeast(stub)
                val top = size.height - h
                when {
                    bar.mmPerHour < PrecipColors.DRAWN_FROM_MM ->
                        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(left, top), Size(barWidth, h), radius)
                    bar.unconfirmed -> drawRoundRect(
                        Color.White.copy(alpha = 0.6f), Offset(left, top), Size(barWidth, h), radius,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                    bar.kind == BarKind.OBSERVED -> drawRoundRect(RibbonColors.OBSERVED, Offset(left, top), Size(barWidth, h), radius)
                    else -> {
                        drawRoundRect(RibbonColors.FORECAST.copy(alpha = 0.45f), Offset(left, top), Size(barWidth, h), radius)
                        var y = size.height - 1.dp.toPx()
                        while (y > top) {
                            drawLine(RibbonColors.FORECAST, Offset(left, y), Offset(left + barWidth, y), strokeWidth = 1.dp.toPx())
                            y -= 3.dp.toPx()
                        }
                    }
                }
                bar.upperMmPerHour?.takeIf { it > bar.mmPerHour && it >= PrecipColors.DRAWN_FROM_MM }?.let { upper ->
                    val capY = size.height - RibbonModel.fraction(upper) * size.height
                    drawLine(RibbonColors.FORECAST.copy(alpha = 0.6f), Offset(left, capY), Offset(left + barWidth, capY), strokeWidth = 1.dp.toPx())
                }
                if (i == selected) {
                    drawRoundRect(
                        Color.White, Offset(left - 1.5f.dp.toPx(), top - 1.5f.dp.toPx()),
                        Size(barWidth + 3.dp.toPx(), h + 3.dp.toPx()), radius, style = Stroke(1.5f.dp.toPx()),
                    )
                }
            }
            if (nowIndex in bars.indices) {
                val x = nowIndex * pitch + pitch / 2f
                drawLine(Color.White, Offset(x, -2.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        RibbonLabels(bars.size, labels)
    }
}

/** Hour labels centred under their bars, each clamped inside the ribbon's width. */
@Composable
private fun RibbonLabels(count: Int, labels: List<Pair<Int, String>>) {
    Layout(
        content = {
            labels.forEach { (_, text) ->
                Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            if (count == 0) return@layout
            val pitch = width.toFloat() / count
            placeables.forEachIndexed { n, p ->
                val centre = labels[n].first * pitch + pitch / 2f
                p.placeRelative((centre - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Screenshot test**

`app/src/test/kotlin/it/apexweather/ui/screenshot/MapRibbonScreenshotTest.kt`:

```kotlin
package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.ui.map.BarKind
import it.apexweather.ui.map.RainRibbon
import it.apexweather.ui.map.RibbonBar
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The ribbon in the three states of the brainstorm mockup. Look at each PNG after recording: a
 * golden nobody looked at proves nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class MapRibbonScreenshotTest {

    private val t0 = Instant.parse("2026-09-14T04:00:00Z")

    private fun bars(vararg spec: Pair<BarKind, Double>, unconfirmedFrom: Int = Int.MAX_VALUE, upper: Double? = null) =
        spec.mapIndexed { i, (kind, mm) -> RibbonBar(t0.plusSeconds(i * 900L), kind, mm, upper?.takeIf { kind == BarKind.OUTLOOK && mm > 0 }, i >= unconfirmedFrom && mm > 0) }

    private fun capture(bars: List<RibbonBar>, selected: Int, now: Int) = captureRoboImage {
        Box(Modifier.background(Color(0xFF0B1020)).padding(16.dp).width(320.dp)) {
            RainRibbon(bars, selected, now, listOf(0 to "06", 4 to "07", 8 to "08", 12 to "09", 16 to "10"), "", onSelect = {})
        }
    }

    private val o = BarKind.OBSERVED
    private val n = BarKind.NOWCAST

    @Test
    fun `a forecast step selected`() = capture(
        bars(o to 0.0, o to 0.0, o to 0.0, o to 0.5, o to 1.3, o to 0.0, o to 0.0, o to 0.0, o to 0.0,
            n to 0.0, n to 0.6, n to 1.4, n to 1.0, n to 0.35, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0),
        selected = 11, now = 8,
    )

    @Test
    fun `this morning, unconfirmed by the radar`() = capture(
        bars(o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0, o to 0.0,
            n to 0.96, n to 0.32, n to 0.12, n to 0.0, n to 0.0, n to 0.2, n to 0.0, n to 0.0, n to 0.0, n to 0.0, n to 0.0,
            unconfirmedFrom = 9),
        selected = 10, now = 8,
    )

    @Test
    fun `Heute, with the wetter end of the ensemble`() = capture(
        bars(*(0 until 24).map { h -> BarKind.OUTLOOK to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3, 0.8, 2.0, 1.2, 0.2).getOrElse(h) { 0.0 } }.toTypedArray(), upper = 4.0),
        selected = 9, now = 0,
    )
}
```

- [ ] **Step 4: Record and look**

Run: `./gradlew :app:recordRoborazziDebug --tests 'it.apexweather.ui.screenshot.MapRibbonScreenshotTest'`
Expected: three PNGs under `app/src/test/screenshots/`. Open each with the Read tool. Check: blue past bars, amber hatched forecast, dashed outlines in the second, amber caps in the third, a white "jetzt" line, a white outline on the selected bar, labels inside the width. Fix and re-record until they read right.

- [ ] **Step 5: Verify**

Run: `./gradlew :app:verifyRoborazziDebug`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/RainRibbon.kt app/src/test/kotlin/it/apexweather/ui/screenshot/MapRibbonScreenshotTest.kt app/src/test/screenshots/
git commit -m "feat: the rain ribbon composable, with goldens of its three states"
```

---

### Task 10: The timeline card, rebuilt around the ribbon

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt` (`MapScreen`, `MapContent`, `Timeline`, `FrameKindChip`, `TimelineTrack` removed, `PrecipLegend`)
- Modify: `app/src/main/res/values{,-it,-en}/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt`

**Interfaces:**
- Consumes: `RainRibbon` (Task 9), `RibbonModel`, `RainWord` (Task 8), `MapZoom`, `MapUiState.withZoom`, `presentTime` (Task 7), `MapViewModel.setZoom`.
- Produces: `MapContent(state, onPlayPause, onSelect, onZoom: (MapZoom) -> Unit = {}, ready: Boolean = true)`; tags `map_ribbon`, `map_zoom_now`, `map_zoom_today`, `map_frame_time`, `map_frame_kind`, `map_place_word`, `map_play_pause`, `map_play_loading`, `map_legend`, `map_attribution`, `map_recenter`, `map_radar_overrule`.

- [ ] **Step 1: Strings**

`values/strings.xml`: rename `map_kind_forecast` → `map_kind_nowcast` with value `Nowcast`, rename `map_kind_outlook` → `map_kind_ensemble` with value `Ensemble`, and add:

```xml
    <string name="map_zoom_now">Jetzt</string>
    <string name="map_zoom_today">Heute</string>
    <string name="map_in">in %1$s</string>
    <string name="map_ago">vor %1$s</string>
    <string name="map_now">jetzt</string>
    <string name="map_rain_dry">trocken</string>
    <string name="map_rain_possible">Regen möglich</string>
    <string name="map_rain_light">leichter Regen</string>
    <string name="map_rain_moderate">Regen</string>
    <string name="map_rain_heavy">starker Regen</string>
    <string name="map_up_to">bis %1$s mm/h</string>
    <string name="map_legend_moderate">mäßig</string>
```

`values-it/strings.xml`: `map_kind_nowcast` `Nowcast`, `map_kind_ensemble` `Ensemble`, and:

```xml
    <string name="map_zoom_now">Adesso</string>
    <string name="map_zoom_today">Oggi</string>
    <string name="map_in">tra %1$s</string>
    <string name="map_ago">%1$s fa</string>
    <string name="map_now">adesso</string>
    <string name="map_rain_dry">asciutto</string>
    <string name="map_rain_possible">pioggia possibile</string>
    <string name="map_rain_light">pioggia debole</string>
    <string name="map_rain_moderate">pioggia</string>
    <string name="map_rain_heavy">pioggia forte</string>
    <string name="map_up_to">fino a %1$s mm/h</string>
    <string name="map_legend_moderate">moderata</string>
```

`values-en/strings.xml`: `map_kind_nowcast` `Nowcast`, `map_kind_ensemble` `Ensemble`, and:

```xml
    <string name="map_zoom_now">Now</string>
    <string name="map_zoom_today">Today</string>
    <string name="map_in">in %1$s</string>
    <string name="map_ago">%1$s ago</string>
    <string name="map_now">now</string>
    <string name="map_rain_dry">dry</string>
    <string name="map_rain_possible">rain possible</string>
    <string name="map_rain_light">light rain</string>
    <string name="map_rain_moderate">rain</string>
    <string name="map_rain_heavy">heavy rain</string>
    <string name="map_up_to">up to %1$s mm/h</string>
    <string name="map_legend_moderate">moderate</string>
```

Then `grep -rn "map_kind_forecast\|map_kind_outlook" app/src` must print nothing once Step 3 is done.

- [ ] **Step 2: Update the device tests first**

In `MapContentTest`:
- replace `"map_scrubber"` with `"map_ribbon"` in `theTimelineAndTheAttributionAreBothOnScreen` and `oneFrameShowsNoScrubber` (rename the latter `oneFrameShowsNoRibbon`);
- in `aFrameBeyondTheRadarIsLabelledAsForecast`, use `R.string.map_kind_nowcast`;
- add:

```kotlin
    @Test
    fun theZoomChipsReportTheirChoice() {
        var chosen: MapZoom? = null
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 0), onPlayPause = {}, onSelect = {}, onZoom = { chosen = it }) } }
        rule.onNodeWithTag("map_zoom_today").performClick()
        assertTrue(chosen == MapZoom.TODAY)
    }

    /** The place's own word is on the card, so "does it reach me" needs no playing. */
    @Test
    fun theCardNamesThePlaceAndItsRain() {
        rule.setContent { ApexTheme { MapContent(withForecast(selected = 16), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_place_word").assertIsDisplayed()
    }

    @Test
    fun whileTilesLoadThePlayButtonSaysSo() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}, ready = false) } }
        rule.onNodeWithTag("map_play_loading").assertIsDisplayed()
    }
```

- [ ] **Step 3: Rewrite the card in `MapScreen.kt`**

`MapScreen` passes the zoom through:

```kotlin
    MapContent(
        state,
        onPlayPause = { if (state.playing) viewModel.pause() else viewModel.play() },
        onSelect = viewModel::select,
        onZoom = viewModel::setZoom,
    )
```

`MapContent` gains `onZoom: (MapZoom) -> Unit = {}` and `ready: Boolean = true` and passes both to `Timeline`. Its attribution `Text` changes only its colour, to `Color.White.copy(alpha = 0.6f)`: the full credit stays, because RainViewer and CC BY 4.0 require it.

Replace `Timeline`, `FrameKindChip`, `TimelineTrack` and `PrecipLegend` with:

```kotlin
/**
 * The card: play, when and what, the place's own rain, the zoom, the ribbon and the legend.
 *
 * It answers in this order: when is this, is it seen or expected, and what does it mean for the
 * reader's place. The last used to take pressing play and watching the map.
 */
@Composable
private fun Timeline(state: MapUiState, ready: Boolean, onPlayPause: () -> Unit, onSelect: (Int) -> Unit, onZoom: (MapZoom) -> Unit) {
    val formats = LocalFormats.current
    val bars = remember(state.visible, state.check, state.place) { RibbonModel.bars(state) }
    val bar = bars.getOrNull(state.selected)
    val word = bar?.let(RibbonModel::word)
    GlassCard(Modifier.fillMaxWidth().testTag("map_timeline")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                    modifier = Modifier.size(40.dp).testTag("map_play_pause"),
                ) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.map_pause else R.string.map_play),
                    )
                }
                if (!ready) {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(40.dp).testTag("map_play_loading"),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                val present = state.presentTime
                val time = state.frame?.time
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        time?.let { Format.dayTime(it, SouthTyrol.ZONE, present ?: it, formats) }.orEmpty(),
                        style = MaterialTheme.typography.titleLarge, color = Color.White,
                        modifier = Modifier.testTag("map_frame_time"),
                    )
                    if (time != null && present != null && time != present) {
                        val d = java.time.Duration.between(present, time)
                        Text(
                            stringResource(if (d.isNegative) R.string.map_ago else R.string.map_in, Format.shortDuration(d, formats)),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
                if (state.place != null && word != null) {
                    val upTo = bar.takeIf { state.zoom == MapZoom.TODAY }?.upperMmPerHour?.takeIf { it >= PrecipColors.RAIN_FROM_MM }
                    Text(
                        buildString {
                            append(state.place.name(formats.locale))
                            append(" · ")
                            append(stringResource(word.labelRes()))
                            if (upTo != null) append(", ").append(stringResource(R.string.map_up_to, Format.mmValue(upTo, formats)))
                        },
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.testTag("map_place_word"),
                    )
                }
            }
            FrameKindChip(state.frame, state.unconfirmedHere)
        }
        val drySince = state.radarDrySince
        if (state.unconfirmedHere && drySince != null) {
            Text(
                stringResource(R.string.map_radar_sees_nothing, Format.time(drySince, SouthTyrol.ZONE, formats)),
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.testTag("map_radar_overrule"),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoomChip(R.string.map_zoom_now, state.zoom == MapZoom.NOW, "map_zoom_now") { onZoom(MapZoom.NOW) }
            ZoomChip(R.string.map_zoom_today, state.zoom == MapZoom.TODAY, "map_zoom_today") { onZoom(MapZoom.TODAY) }
        }
        if (bars.size > 1) {
            val nowLabel = stringResource(R.string.map_now)
            val labels = RibbonModel.labelIndices(bars, state.zoom)
                .filter { abs(it - state.nowIndex) > 1 }
                .map { it to Format.hour(bars[it].time, SouthTyrol.ZONE, formats) } + (state.nowIndex to nowLabel)
            RainRibbon(
                bars = bars, selected = state.selected, nowIndex = state.nowIndex,
                labels = labels.filter { it.first in bars.indices }.sortedBy { it.first },
                stateDescription = listOfNotNull(
                    state.frame?.time?.let { Format.dayTime(it, SouthTyrol.ZONE, state.presentTime ?: it, formats) },
                    word?.let { stringResource(it.labelRes()) },
                ).joinToString(", "),
                onSelect = onSelect,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        PrecipLegend()
    }
}

@Composable
private fun ZoomChip(label: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color(0xFF111111) else Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag),
    )
}

/** Seen, expected, or expected and not confirmed — said in a word rather than left to colour. */
@Composable
private fun FrameKindChip(frame: MapFrame?, unconfirmed: Boolean) {
    val label = when {
        unconfirmed -> R.string.map_kind_unconfirmed
        frame is MapFrame.Forecast && frame.step.kind == NowcastKind.OUTLOOK -> R.string.map_kind_ensemble
        frame is MapFrame.Forecast -> R.string.map_kind_nowcast
        else -> R.string.map_kind_radar
    }
    val colour = when {
        unconfirmed -> Color.White.copy(alpha = 0.7f)
        frame is MapFrame.Forecast -> RibbonColors.FORECAST
        else -> Color(0xFF9CC9FF)
    }
    Row(
        Modifier.clip(CircleShape).background(colour.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 3.dp).testTag("map_frame_kind"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun RainWord.labelRes(): Int = when (this) {
    RainWord.DRY -> R.string.map_rain_dry
    RainWord.POSSIBLE -> R.string.map_rain_possible
    RainWord.LIGHT -> R.string.map_rain_light
    RainWord.MODERATE -> R.string.map_rain_moderate
    RainWord.HEAVY -> R.string.map_rain_heavy
}

/** What the colours mean, in words: the numbers are Marshall–Palmer's, and "leicht" claims less. */
@Composable
private fun PrecipLegend() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("map_legend")) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawRoundRect(brush = Brush.horizontalGradient(PrecipColors.RAMP), cornerRadius = CornerRadius(size.height / 2f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(R.string.map_legend_light, R.string.map_legend_moderate, R.string.map_legend_heavy).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}
```

`Place.name(locale)` and `Format.mmValue(mm, f)` already exist and are what the card uses.

Imports to add: `androidx.compose.foundation.clickable`, `androidx.compose.material3.CircularProgressIndicator`, `kotlin.math.abs`. Remove: `Slider`, `SliderDefaults`, `PathEffect`, `StrokeCap`, `ExperimentalMaterial3Api` opt-in.

- [ ] **Step 4: Build, then device tests**

Run: `./gradlew :app:assembleDebug` then `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.map.MapContentTest`
Expected: PASS.

- [ ] **Step 5: On the phone at font scale 1 and 2**

Install, open Karte, screenshot (`adb -s RZCXA1ZEXJE exec-out screencap -p`) and Read it. Then `adb -s RZCXA1ZEXJE shell settings put system font_scale 2.0`, screenshot again, and restore with `settings put system font_scale 1.0`. Nothing may clip or overlap. Decide the Heute label hours from Task 8 Step 6 here and adjust `RibbonModelTest` if needed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt app/src/main/kotlin/it/apexweather/ui/common/Format.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt
git commit -m "feat: the map's timeline card is built around the rain ribbon"
```

---

### Task 11: Crossfade and preload

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/map/FrameLayers.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt` (`MapContent`, `RadarMap`)
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt` (fade multiplier)
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt`, `MapViewModel.kt` (`animations`)

**Interfaces:**
- Consumes: `MapUiState.visible` (Task 7), `RadarTileSource`, `fetchRadarParents`.
- Produces: `NowcastOverlay.fade: Float`; `MapUiState.animations: Boolean = true`; `internal class FrameLayers(map: MapView)` with `fun show(frame: MapFrame?, motion: Boolean)`, `fun preload(frames: List<MapFrame>, from: Int): Boolean`, `fun release()`.

- [ ] **Step 1: `animations` into the state**

`MapUiState` constructor: add `val animations: Boolean = true,`. In `MapViewModel.init`'s collector: `_state.update { it.copy(place = home.place, animations = home.settings.animations) }`.

- [ ] **Step 2: Fade multiplier on `NowcastOverlay`**

Add `var fade: Float = 1f` as a public property, and in `draw` multiply the final alpha: `paint.color = colour.toArgb((strength * colour.alpha * fade).toInt())`.

- [ ] **Step 3: `FrameLayers.kt`**

```kotlin
package it.apexweather.ui.map

import android.animation.ValueAnimator
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import it.apexweather.data.remote.RainViewerMapper
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * The rain layer of the MapView: one frame on screen, the next one faded in over it, and every
 * frame of the zoom's tiles fetched before the loop needs them.
 *
 * The old code removed the overlay and added the next on every frame, so each frame arrived as a
 * blank map until its tiles did and there was no transition at all. A provider per frame is kept
 * for the life of the frame list, which is what lets the tiles already be there.
 */
internal class FrameLayers(private val map: MapView) {

    private val providers = HashMap<Instant, MapTileProviderBasic>()
    private var shown: Overlay? = null
    private var shownTime: Instant? = null
    private var fading: ValueAnimator? = null

    fun show(frame: MapFrame?, motion: Boolean) {
        if (frame?.time == shownTime && frame != null) return
        fading?.end()
        val incoming = frame?.let(::overlayFor)
        val outgoing = shown
        shown = incoming
        shownTime = frame?.time
        if (incoming != null) map.overlays.add(0, incoming)
        if (!motion || outgoing == null || incoming == null) {
            outgoing?.let { map.overlays.remove(it) }
            incoming?.let { setFade(it, 1f) }
            map.invalidate()
            return
        }
        setFade(incoming, 0f)
        fading = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener { a ->
                setFade(incoming, a.animatedValue as Float)
                setFade(outgoing, 1f - (a.animatedValue as Float))
                map.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    map.overlays.remove(outgoing)
                    map.invalidate()
                }
            })
            start()
        }
    }

    /**
     * Asks every radar frame from [from] onwards for the tiles the map is showing, parents included.
     * True once the next [READY_AHEAD] frames have them in memory.
     */
    fun preload(frames: List<MapFrame>, from: Int): Boolean {
        val zoom = minOf(map.zoomLevelDouble.toInt(), RainViewerMapper.MAX_ZOOM)
        val box = map.boundingBox
        val indices = buildList {
            for (x in tileX(box.lonWest, zoom)..tileX(box.lonEast, zoom)) {
                for (y in tileY(box.latNorth, zoom)..tileY(box.latSouth, zoom)) add(MapTileIndex.getTileIndex(zoom, x, y))
            }
        }
        var ready = true
        frames.drop(from.coerceAtLeast(0)).filterIsInstance<MapFrame.Observed>().forEachIndexed { n, frame ->
            val provider = providerFor(frame)
            indices.forEach { provider.getMapTile(it) }
            if (map.zoomLevelDouble > RainViewerMapper.MAX_ZOOM) provider.fetchRadarParents(map)
            if (n < READY_AHEAD && indices.any { provider.tileCache.getMapTile(it) == null }) ready = false
        }
        providers.entries.removeAll { (t, provider) ->
            frames.none { it.time == t }.also { gone -> if (gone) provider.detach() }
        }
        return ready
    }

    fun release() {
        fading?.cancel()
        providers.values.forEach { it.detach() }
        providers.clear()
    }

    private fun providerFor(frame: MapFrame.Observed): MapTileProviderBasic =
        providers.getOrPut(frame.time) {
            MapTileProviderBasic(map.context, RadarTileSource(frame.radar)).apply {
                setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
                tileCache.ensureCapacity(TILE_CAPACITY)
            }
        }

    private fun overlayFor(frame: MapFrame): Overlay = when (frame) {
        is MapFrame.Observed -> TilesOverlay(providerFor(frame), map.context).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            providerFor(frame).fetchRadarParents(map)
        }
        is MapFrame.Forecast -> NowcastOverlay(frame.step, NOWCAST_ALPHA)
    }

    private fun setFade(overlay: Overlay, f: Float) {
        when (overlay) {
            is TilesOverlay -> overlay.setColorFilter(radarFilter(RADAR_ALPHA * f))
            is NowcastOverlay -> overlay.fade = f
        }
    }

    private fun radarFilter(alpha: Float) = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, alpha, 0f)),
    )

    private fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    companion object {
        const val FADE_MS = 250L
        const val READY_AHEAD = 3

        /** One zoom's worth: thirteen frames of a handful of tiles each, and no more. */
        const val TILE_CAPACITY = 40

        /** How much of the radar is let through, so the valley under the rain stays visible. */
        const val RADAR_ALPHA = 0.62f

        /** And of the forecast, out of 255: a shade lighter than the radar on purpose. */
        const val NOWCAST_ALPHA = 130
    }
}
```

In `MapScreen.kt` delete the file-level `RADAR_ALPHA` and `NOWCAST_ALPHA` constants (they move to `FrameLayers`).

- [ ] **Step 4: Use it in `RadarMap`**

`MapContent` holds readiness and passes it both ways:

```kotlin
    var ready by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().testTag("map_screen")) {
        RadarMap(state, recenter.value, onReady = { ready = it }, Modifier.fillMaxSize())
        // ... Timeline(state, ready, onPlayPause, onSelect, onZoom) where it was
```

In `RadarMap(state, recenter, onReady: (Boolean) -> Unit, modifier)`:

```kotlin
    val layers = remember { FrameLayers(mapView) }
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) == 0f
    }
    val motion = state.animations && !reduceMotion
```

- Replace `radarProvider` and its `MapListener` body with `layers.preload(state.visible, state.selected)` in both `onScroll` and `onZoom` (read the latest state through `rememberUpdatedState(state)`).
- In `DisposableEffect`'s `onDispose`, call `layers.release()` before `mapView.onDetach()`.
- Add the preload loop:

```kotlin
    LaunchedEffect(state.visible, state.zoom) {
        onReady(false)
        while (!layers.preload(state.visible, state.selected)) kotlinx.coroutines.delay(250)
        onReady(true)
    }
```

- In `AndroidView.update`, replace everything from `// One rain overlay at a time` to `map.invalidate()` with `layers.show(state.frame, motion)`.

- [ ] **Step 5: Build and run the existing tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest && ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.map.MapContentTest`
Expected: PASS.

- [ ] **Step 6: On the phone (no unit test can see this)**

Install. On Karte: wait for the ring to go, press play, watch two full loops at zoom 10 and one after pinching to 13. No frame may flash blank; frames must dissolve, not jump. Switch animations off in Mehr, return: frames switch instantly. Record what you saw in the commit message.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/FrameLayers.kt app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt app/src/main/kotlin/it/apexweather/ui/map/MapState.kt app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt
git commit -m "feat: map frames crossfade, and play waits for their tiles"
```

---

### Task 12: A softer forecast overlay

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt` (`draw`)

**Interfaces:**
- Consumes: Task 11's `fade`; Task 5's `unconfirmed`.
- Produces: nothing new.

- [ ] **Step 1: Paint cells into a small bitmap and draw it filtered**

Replace `draw` with:

```kotlin
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || cells.isEmpty()) return
        val projection = map.projection
        val side = cellSidePx(projection, cells.first().lat, cellKm)
        val bounds = map.boundingBox
        val point = android.graphics.Point()
        // Project once, keep what is on screen and has a colour.
        val placed = cells.mapNotNull { cell ->
            if (cell.lat < bounds.latSouth || cell.lat > bounds.latNorth) return@mapNotNull null
            if (cell.lon < bounds.lonWest || cell.lon > bounds.lonEast) return@mapNotNull null
            val solid = if (cell.unconfirmed) null else PrecipColors.forRate(cell.mmPerHour)
            val possible = if (solid != null) null
                else (if (cell.unconfirmed) cell.mmPerHour else cell.upperMmPerHour)?.let(PrecipColors::forRate)
            val colour = solid ?: possible ?: return@mapNotNull null
            projection.toPixels(GeoPoint(cell.lat, cell.lon), point)
            val strength = if (solid != null) alpha else alpha * POSSIBLE_ALPHA_NUMERATOR / 10
            Triple(point.x.toFloat(), point.y.toFloat(), colour.toArgb((strength * colour.alpha * fade).toInt()))
        }
        if (placed.isEmpty()) return
        // One bitmap pixel per grid cell, drawn scaled with filtering: the edges soften and the grid
        // still reads as coarser than radar, where squares read as pixel blocks.
        val left = placed.minOf { it.first } - side / 2f
        val top = placed.minOf { it.second } - side / 2f
        val cols = ((placed.maxOf { it.first } - left) / side).toInt() + 2
        val rows = ((placed.maxOf { it.second } - top) / side).toInt() + 2
        val bitmap = android.graphics.Bitmap.createBitmap(cols, rows, android.graphics.Bitmap.Config.ARGB_8888)
        placed.forEach { (x, y, argb) ->
            bitmap.setPixel(((x - left) / side).toInt().coerceIn(0, cols - 1), ((y - top) / side).toInt().coerceIn(0, rows - 1), argb)
        }
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + cols * side, top + rows * side), bitmapPaint)
        bitmap.recycle()
    }
```

Remove the now-unused `paint` field. Update the class doc's first paragraph: cells are drawn as a filtered bitmap, one pixel per grid cell — soft edges, still visibly coarser than the radar.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On the phone**

With forecast rain somewhere in the province (or scrub to an Ensemble hour that has some), compare zoom 9 and zoom 13. Look for holes in a field of rain where projected cells collided in one bitmap pixel. If there are holes, change `side` in the bitmap maths to `side * 0.9f` and look again. Screenshot and Read the result.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/map/NowcastOverlay.kt
git commit -m "feat: the forecast overlay is a filtered grid rather than hard squares"
```

---

### Task 13: Phase 2 verification, docs and release

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: `CLAUDE.md`**

Add to the `ui/map/` bullet, after the fix-B paragraph from Task 6:

```markdown
  **The timeline is a rain ribbon** (`RainRibbon`, `RibbonModel`): a bar per step for the place
  alone — solid blue seen, amber hatched expected, dashed outline expected and not confirmed — so
  whether the rain reaches the reader is on screen before anything plays. Two zooms: *Jetzt* (2 h
  back, 3 h ahead, quarter hours) and *Heute* (24 h of AROME ensemble hours, with the p90 as a cap).
  Amber is forecast chrome only and never a rain colour. **Frames crossfade over 250 ms and play
  waits for the next three frames' tiles** (`FrameLayers`); a provider per frame lives as long as
  the frame list. The animations switch and the system's reduced motion turn the fade off, as they
  do the sky's.
```

- [ ] **Step 2: Full verification**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:verifyRoborazziDebug
./gradlew :app:lintDebug
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease && ./tools/release-smoke.sh RZCXA1ZEXJE
```

Expected: all green.

- [ ] **Step 3: Commit docs**

```bash
git add CLAUDE.md
git commit -m "docs: the map's rain ribbon, zooms and crossfade"
```

- [ ] **Step 4: Release**

Ask the user before tagging. Then, as in Task 6 Step 5, with `v0.23.0`.
