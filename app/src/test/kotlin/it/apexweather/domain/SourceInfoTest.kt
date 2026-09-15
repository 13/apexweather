package it.apexweather.domain

import it.apexweather.data.remote.OpenMeteoMapper
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInfoTest {

    @Test
    fun `every source has facts, a licence and an https website`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertTrue("$s has no licence", info.licence.isNotBlank())
            assertTrue("$s website ${info.website}", info.website.startsWith("https://"))
            assertTrue("$s provider is blank", info.provider == null || info.provider.isNotBlank())
        }
    }

    /** The metadata dataset is how the sheet finds a run time; it must exist exactly where Open-Meteo delivers. */
    @Test
    fun `Open-Meteo sources and only they carry a metadata dataset`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertEquals("$s delivery", s in OpenMeteoMapper.MODELS, info.delivery == Delivery.OPEN_METEO)
            if (info.delivery == Delivery.OPEN_METEO) assertNotNull("$s dataset", info.metaDataset) else assertNull("$s dataset", info.metaDataset)
        }
        val datasets = Source.entries.mapNotNull { SourceInfo.of(it).metaDataset }
        assertEquals("a dataset is named twice", datasets.size, datasets.toSet().size)
    }

    @Test
    fun `only KMOS is not a grid, and only the province goes unnamed`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertEquals("$s grid", s == Source.SIAG_KMOS, info.gridKm == null)
            assertEquals("$s provider", s == Source.SIAG_KMOS, info.provider == null)
        }
        assertEquals(Delivery.SIAG, SourceInfo.of(Source.SIAG_KMOS).delivery)
        assertEquals(Delivery.GEOSPHERE, SourceInfo.of(Source.GEOSPHERE_AROME).delivery)
    }

    /** The one Open-Meteo source whose licence is not CC BY. */
    @Test
    fun `UK Met Office data is share-alike`() {
        assertEquals("CC BY-SA 4.0", SourceInfo.of(Source.UKMO).licence)
        assertEquals("CC BY 4.0", SourceInfo.of(Source.ICON_D2).licence)
    }
}
