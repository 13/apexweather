package it.apexweather.data.remote

import it.apexweather.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Recorded 2026-09-15 from the live endpoints; see SourceInfo for where each URL comes from. */
class SourceMetaMapperTest {

    private fun fixture(name: String): JsonObject = Fixtures.json.parseToJsonElement(Fixtures.read(name)).jsonObject

    @Test
    fun `ICON-D2's metadata gives the run, its publication and the three-hour cycle`() {
        val meta = SourceMetaMapper.openMeteo(fixture("meta_dwd_icon_d2.json"))
        assertEquals(Instant.parse("2026-09-15T00:00:00Z"), meta.runStartedAt)
        assertEquals(Instant.parse("2026-09-15T01:27:51Z"), meta.publishedAt)
        assertEquals(Duration.ofHours(3), meta.updateEvery)
    }

    @Test
    fun `ECMWF IFS runs every six hours`() {
        val meta = SourceMetaMapper.openMeteo(fixture("meta_ecmwf_ifs025.json"))
        assertEquals(Instant.parse("2026-09-14T18:00:00Z"), meta.runStartedAt)
        assertEquals(Instant.parse("2026-09-15T01:11:44Z"), meta.publishedAt)
        assertEquals(Duration.ofHours(6), meta.updateEvery)
    }

    @Test
    fun `GeoSphere gives the newest reference time and the gap to the one before`() {
        val meta = SourceMetaMapper.geoSphere(fixture("geosphere_nwp_metadata.json"))
        assertEquals(Instant.parse("2026-09-14T21:00:00Z"), meta.runStartedAt)
        assertNull("GeoSphere publishes no publication time", meta.publishedAt)
        assertEquals(Duration.ofHours(3), meta.updateEvery)
    }

    /** A field the provider drops or garbles costs that line of the sheet and nothing else. */
    @Test
    fun `a missing or malformed field is null, not a failure`() {
        val meta = SourceMetaMapper.openMeteo(buildJsonObject {
            put("last_run_initialisation_time", 1789430400)
            put("update_interval_seconds", "soon")
        })
        assertEquals(Instant.parse("2026-09-15T00:00:00Z"), meta.runStartedAt)
        assertNull(meta.publishedAt)
        assertNull(meta.updateEvery)
    }

    @Test
    fun `GeoSphere with a single reference time has no interval`() {
        val meta = SourceMetaMapper.geoSphere(buildJsonObject {
            putJsonArray("available_forecast_reftimes") { add("2026-09-14T21:00+00:00") }
        })
        assertEquals(Instant.parse("2026-09-14T21:00:00Z"), meta.runStartedAt)
        assertNull(meta.updateEvery)
    }
}
