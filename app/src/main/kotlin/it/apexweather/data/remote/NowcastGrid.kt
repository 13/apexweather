package it.apexweather.data.remote

import io.jhdf.HdfFile
import io.jhdf.api.Dataset
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * GeoSphere's rain forecast as the NetCDF 4 (HDF5) file the grid service offers beside GeoJSON.
 *
 * **It is the only form in which the whole province is affordable.** Measured on 2026-09-16 over
 * South Tyrol's bounding box: INCA 4,4 MB as GeoJSON (12 s) against 162 kB here (0,3 s), and AROME's
 * two percentiles for a day 2,2 MB against 270 kB. The service compresses neither response on the
 * wire; the NetCDF is deflated inside. Region-wide is what the radar shows, and a forecast cut to a
 * box round the place read on the map as a square of weather rather than as the rain coming.
 *
 * Both files have the same shape: `time` (offsets from a stated origin), `leadtime` (hours after
 * the run), `lat`/`lon`, and the rain as scaled integers with a `scale_factor` attribute and
 * `-999` for missing. INCA's grid is a projected one, so its `lat`/`lon` are two-dimensional; the
 * ensemble's is regular and they are one-dimensional. Values were checked point by point against
 * the GeoJSON of the same run before this was trusted.
 */
object NowcastGrid {

    /** [onError] hears why a file could not be read; the map then simply has no forecast. */
    fun map(bytes: ByteArray, kind: NowcastKind, onError: (Throwable) -> Unit = {}): PrecipNowcast = runCatching {
        HdfFile.fromBytes(bytes).use { hdf -> read(hdf, kind) }
    }.getOrElse { onError(it); PrecipNowcast.EMPTY }

    private fun read(hdf: HdfFile, kind: NowcastKind): PrecipNowcast {
        val timeSet = hdf.getDatasetByPath("time")
        val times = timesOf(timeSet)
        if (times.isEmpty()) return PrecipNowcast.EMPTY
        val leadHours = doubles(hdf.getDatasetByPath("leadtime"))
        val issuedAt = times.first().minusSeconds((leadHours.first() * 3600).toLong())

        val latSet = hdf.getDatasetByPath("lat")
        val lats = doubles(latSet)
        val lons = doubles(hdf.getDatasetByPath("lon"))
        val (median, upper) = when (kind) {
            NowcastKind.NOWCAST -> hdf.getDatasetByPath(NOWCAST_PARAMETER) to null
            NowcastKind.OUTLOOK -> hdf.getDatasetByPath(NowcastMapper.OUTLOOK_PARAMETER) to
                hdf.getDatasetByPath(NowcastMapper.OUTLOOK_UPPER_PARAMETER)
        }
        val dims = median.dimensions
        val rows = dims[1]
        val cols = dims[2]
        // A projected grid carries a latitude and longitude per point; a regular one per row and
        // per column.
        val twoDimensional = latSet.dimensions.size == 2
        fun latAt(r: Int, c: Int) = if (twoDimensional) lats[r * cols + c] else lats[r]
        fun lonAt(r: Int, c: Int) = if (twoDimensional) lons[r * cols + c] else lons[c]

        val values = scaled(median)
        val uppers = upper?.let(::scaled)
        // Quarter-hour sums for INCA, hourly ones for AROME: both become a rate.
        val perHour = if (kind == NowcastKind.NOWCAST) 4.0 else 1.0
        val extent = NowcastExtent(
            south = lats.filter { !it.isNaN() }.min(), west = lons.filter { !it.isNaN() }.min(),
            north = lats.filter { !it.isNaN() }.max(), east = lons.filter { !it.isNaN() }.max(),
        )
        val perStep = rows * cols
        val steps = times.mapIndexed { t, time ->
            val cells = ArrayList<NowcastCell>()
            for (r in 0 until rows) for (c in 0 until cols) {
                val i = t * perStep + r * cols + c
                val rate = values[i] * perHour
                val high = uppers?.get(i)?.times(perHour)
                // NaN (missing) compares false, so a missing value is dropped with the dry ones.
                val worthDrawing = rate >= NowcastMapper.MIN_MM_PER_HOUR ||
                    (high != null && high >= NowcastMapper.MIN_MM_PER_HOUR)
                if (!worthDrawing) continue
                val lat = latAt(r, c)
                val lon = lonAt(r, c)
                if (lat.isNaN() || lon.isNaN()) continue
                cells += NowcastCell(lat, lon, if (rate.isNaN()) 0.0 else rate, high?.takeUnless { it.isNaN() })
            }
            NowcastStep(time, cells, kind, extent)
        }
        return PrecipNowcast(issuedAt, steps)
    }

    /** `units` reads e.g. "minutes since 2026-09-16 17:45:00"; the origin is UTC. */
    private fun timesOf(set: Dataset): List<Instant> {
        val units = stringAttribute(set, "units") ?: return emptyList()
        val (unit, origin) = UNITS.matchEntire(units.trim())?.destructured ?: return emptyList()
        val seconds = when (unit) {
            "seconds" -> 1L
            "minutes" -> 60L
            "hours" -> 3600L
            else -> return emptyList()
        }
        val start = LocalDateTime.parse(origin, ORIGIN).toInstant(ZoneOffset.UTC)
        return longs(set).map { start.plusSeconds(it * seconds) }
    }

    /** The values with `scale_factor` applied, and NaN for the fill value. */
    private fun scaled(set: Dataset): DoubleArray {
        val scale = numberAttribute(set, "scale_factor") ?: 1.0
        val fill = numberAttribute(set, "_FillValue")
        return when (val raw = set.dataFlat) {
            is ShortArray -> DoubleArray(raw.size) { i -> raw[i].toDouble().let { if (it == fill) Double.NaN else it * scale } }
            is IntArray -> DoubleArray(raw.size) { i -> raw[i].toDouble().let { if (it == fill) Double.NaN else it * scale } }
            else -> doubles(set).map { if (it == fill) Double.NaN else it * scale }.toDoubleArray()
        }
    }

    private fun doubles(set: Dataset): DoubleArray = when (val raw = set.dataFlat) {
        is DoubleArray -> raw
        is FloatArray -> DoubleArray(raw.size) { raw[it].toDouble() }
        is IntArray -> DoubleArray(raw.size) { raw[it].toDouble() }
        is ShortArray -> DoubleArray(raw.size) { raw[it].toDouble() }
        is LongArray -> DoubleArray(raw.size) { raw[it].toDouble() }
        else -> error("unexpected ${raw?.javaClass}")
    }

    private fun longs(set: Dataset): LongArray = when (val raw = set.dataFlat) {
        is LongArray -> raw
        is IntArray -> LongArray(raw.size) { raw[it].toLong() }
        else -> doubles(set).map { it.toLong() }.toLongArray()
    }

    private fun stringAttribute(set: Dataset, name: String): String? =
        when (val v = set.getAttribute(name)?.data) {
            is String -> v
            is Array<*> -> v.firstOrNull() as? String
            else -> null
        }

    private fun numberAttribute(set: Dataset, name: String): Double? =
        when (val v = set.getAttribute(name)?.data) {
            is Number -> v.toDouble()
            is DoubleArray -> v.firstOrNull()
            is FloatArray -> v.firstOrNull()?.toDouble()
            is IntArray -> v.firstOrNull()?.toDouble()
            is ShortArray -> v.firstOrNull()?.toDouble()
            is LongArray -> v.firstOrNull()?.toDouble()
            else -> null
        }

    /** INCA's parameter, a sum over its quarter-hour step. */
    private const val NOWCAST_PARAMETER = "rr"

    private val UNITS = Regex("(seconds|minutes|hours) since (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})")
    private val ORIGIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
