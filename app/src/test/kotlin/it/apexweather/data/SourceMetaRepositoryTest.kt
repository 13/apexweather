package it.apexweather.data

import it.apexweather.Fixtures
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.domain.model.Source
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class SourceMetaRepositoryTest {

    private class FakeApi : SourceMetaApi {
        val urls = mutableListOf<String>()
        var fail = false
        var cancel = false
        override suspend fun metadata(url: String): JsonObject {
            urls += url
            if (cancel) throw CancellationException("the sheet was closed")
            if (fail) throw IOException("offline")
            val name = if ("geosphere" in url) "geosphere_nwp_metadata.json" else "meta_dwd_icon_d2.json"
            return Fixtures.json.parseToJsonElement(Fixtures.read(name)).jsonObject
        }
    }

    private val t0 = Instant.parse("2026-09-15T08:00:00Z")

    /** The fake answers whatever it is asked; this is what proves the right thing was asked. */
    @Test
    fun `each source is asked for at its own URL`() = runTest {
        val api = FakeApi()
        val repo = SourceMetaRepository(api, MutableClock(t0))
        repo.metaFor(Source.ICON_D2)
        repo.metaFor(Source.GFS)
        repo.metaFor(Source.GEOSPHERE_AROME)
        assertEquals(
            listOf(
                "https://api.open-meteo.com/data/dwd_icon_d2/static/meta.json",
                "https://api.open-meteo.com/data/ncep_gfs013/static/meta.json",
                "https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m/metadata",
            ),
            api.urls,
        )
    }

    @Test
    fun `an answer is kept for ten minutes`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t0)
        val repo = SourceMetaRepository(api, clock)
        assertNotNull(repo.metaFor(Source.ICON_D2))
        clock.now = t0.plus(Duration.ofMinutes(9))
        assertNotNull(repo.metaFor(Source.ICON_D2))
        assertEquals("asked again inside ten minutes", 1, api.urls.size)
        clock.now = t0.plus(Duration.ofMinutes(11))
        repo.metaFor(Source.ICON_D2)
        assertEquals(2, api.urls.size)
    }

    @Test
    fun `a failure is null and the next ask tries again`() = runTest {
        val api = FakeApi().apply { fail = true }
        val repo = SourceMetaRepository(api, MutableClock(t0))
        assertNull(repo.metaFor(Source.ICON_D2))
        api.fail = false
        assertNotNull(repo.metaFor(Source.ICON_D2))
        assertEquals(2, api.urls.size)
    }

    /** KMOS's run time is already its status time; there is nothing to ask. */
    @Test
    fun `KMOS is never asked`() = runTest {
        val api = FakeApi()
        assertNull(SourceMetaRepository(api, MutableClock(t0)).metaFor(Source.SIAG_KMOS))
        assertTrue(api.urls.isEmpty())
    }

    /** Plain runCatching would swallow this and return null; a closed sheet must stop the fetch. */
    @Test
    fun `cancellation is passed on, not turned into a missing answer`() = runTest {
        val api = FakeApi().apply { cancel = true }
        val thrown = runCatching { SourceMetaRepository(api, MutableClock(t0)).metaFor(Source.ICON_D2) }.exceptionOrNull()
        assertTrue("got $thrown", thrown is CancellationException)
    }
}
