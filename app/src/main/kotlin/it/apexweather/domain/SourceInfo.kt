package it.apexweather.domain

import it.apexweather.domain.model.Source

/** Which upstream the app fetches a source's forecast from. */
enum class Delivery { OPEN_METEO, GEOSPHERE, SIAG }

/**
 * What a source is, in facts that do not change from one refresh to the next — for the source sheet
 * on the comparison screen.
 *
 * Every value was checked on 2026-09-15 against the provider's documentation, not written from
 * memory: resolutions and update cycles from Open-Meteo's model pages (`open-meteo.com/en/docs/…`)
 * and GeoSphere's dataset metadata (`spatial_resolution_m` 2500), licences from
 * `open-meteo.com/en/licence` ("API data are offered under CC BY 4.0", and the UKMO page: "UK Met
 * Office data is provided under the CC BY-SA 4.0 licence"), KMOS's CC0 from the Open Data Hub
 * record's `LicenseInfo`, and every website answered HTTP 200.
 *
 * **[metaDataset] is not the model id of the forecast call.** Open-Meteo files run metadata under
 * `/data/<dataset>/static/meta.json`, and the names differ: the forecast asks for `icon_d2`, the
 * metadata lives under `dwd_icon_d2`; `gfs_seamless` has none and GFS's global run is `ncep_gfs013`.
 * All eleven answered HTTP 200 on 2026-09-14.
 */
data class SourceInfo(
    /** The institution, as a proper noun. Null for the province's own weather service, whose name is per language. */
    val provider: String?,
    /** Horizontal grid spacing in kilometres; null where the forecast is not a grid (KMOS is per municipality). */
    val gridKm: Double?,
    val delivery: Delivery,
    /** The licence of the data as this app receives it. */
    val licence: String,
    val website: String,
    /** Open-Meteo's metadata dataset; null for every other delivery. */
    val metaDataset: String? = null,
) {
    companion object {
        fun of(source: Source): SourceInfo = TABLE.getValue(source)

        private const val CC_BY = "CC BY 4.0"

        private val TABLE: Map<Source, SourceInfo> = mapOf(
            Source.SIAG_KMOS to SourceInfo(null, null, Delivery.SIAG, "CC0", "https://weather.provinz.bz.it"),
            Source.GEOSPHERE_AROME to SourceInfo("GeoSphere Austria", 2.5, Delivery.GEOSPHERE, CC_BY, "https://www.geosphere.at"),
            // MeteoSwiss docs: ICON CH1 0.01° (~1 km), every 3 h; ICON CH2 0.02° (~2 km), every 6 h.
            Source.ICON_CH1 to SourceInfo("MeteoSwiss", 1.0, Delivery.OPEN_METEO, CC_BY, "https://www.meteoswiss.admin.ch", "meteoswiss_icon_ch1"),
            Source.ICON_CH2 to SourceInfo("MeteoSwiss", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.meteoswiss.admin.ch", "meteoswiss_icon_ch2"),
            // ItaliaMeteo-ARPAE docs: ICON 2I 0.02° (~2 km), every 12 h.
            Source.ICON_2I to SourceInfo("ItaliaMeteo-ARPAE", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.arpae.it", "italia_meteo_arpae_icon_2i"),
            // DWD docs: ICON D2 0.02° (~2 km), every 3 h.
            Source.ICON_D2 to SourceInfo("Deutscher Wetterdienst", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.dwd.de", "dwd_icon_d2"),
            // KNMI docs: HARMONIE AROME Europe 5.5 km, every hour (the Netherlands 2 km run does not reach here).
            Source.KNMI_HARMONIE to SourceInfo("KNMI", 5.5, Delivery.OPEN_METEO, CC_BY, "https://www.knmi.nl", "knmi_harmonie_arome_europe"),
            // DMI docs: HARMONIE AROME DINI 2 km, every 3 h.
            Source.DMI_HARMONIE to SourceInfo("DMI", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.dmi.dk", "dmi_harmonie_arome_europe"),
            // ECMWF docs: IFS 0.25° (~25 km), AIFS Single 0.25° (~28 km), both every 6 h.
            Source.ECMWF to SourceInfo("ECMWF", 25.0, Delivery.OPEN_METEO, CC_BY, "https://www.ecmwf.int", "ecmwf_ifs025"),
            Source.ECMWF_AIFS to SourceInfo("ECMWF", 28.0, Delivery.OPEN_METEO, CC_BY, "https://www.ecmwf.int", "ecmwf_aifs025_single"),
            // GFS docs: GFS Global 0.11° (~13 km), every 6 h.
            Source.GFS to SourceInfo("NOAA NCEP", 13.0, Delivery.OPEN_METEO, CC_BY, "https://www.ncei.noaa.gov/products/weather-climate-models/global-forecast", "ncep_gfs013"),
            // UKMO docs: Global 0.09° (~10 km), every 6 h; redistributed CC BY-SA 4.0.
            Source.UKMO to SourceInfo("Met Office", 10.0, Delivery.OPEN_METEO, "CC BY-SA 4.0", "https://www.metoffice.gov.uk", "ukmo_global_deterministic_10km"),
            // GEM docs: GEM Global 0.15° (~15 km), every 12 h. `cmc_gem_gdps`'s meta.json stopped
            // updating in May 2026 — its last run sat at 2026-05-26, published 2026-07-01, while the
            // phone read September — so the sheet showed a four-month-old run as current. Measured
            // 2026-09-15: `cmc_gem_gdps_15km`'s last run was 2026-09-14T12:00Z, published 17:58Z.
            Source.GEM to SourceInfo("ECCC", 15.0, Delivery.OPEN_METEO, CC_BY, "https://weather.gc.ca", "cmc_gem_gdps_15km"),
        )
    }
}
