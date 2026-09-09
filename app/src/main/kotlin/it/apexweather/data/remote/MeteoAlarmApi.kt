package it.apexweather.data.remote

import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import okhttp3.ResponseBody
import org.w3c.dom.Element
import retrofit2.http.GET
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MeteoAlarm's legacy Atom feed for Italy, the European civil-protection warnings in one document.
 *
 * There is a JSON API beside it, but it hands out every warning in the country in full CAP, a
 * megabyte per call. This feed is the same information in 79 kB, and about 11 kB on the wire once
 * gzipped, which is what an hourly background refresh can afford.
 */
interface MeteoAlarmApi {
    @GET("feeds/meteoalarm-legacy-atom-italy")
    suspend fun italy(): ResponseBody

    companion object { const val BASE_URL = "https://feeds.meteoalarm.org/" }
}

object MeteoAlarmMapper {
    /**
     * The EMMA region Dorf Tirol falls in. Italy publishes warnings per region, and the smallest
     * one here is the whole of Trentino-Alto Adige — a warning is for the region, never the village.
     */
    const val REGION = "IT002"

    /**
     * Parses the feed and keeps the warnings for [region] that have not expired at [now].
     *
     * The feed is a public document from a third party, so nothing in it is trusted to be
     * well-formed: an entry missing a field, or carrying a date that will not parse, is dropped
     * rather than allowed to fail the whole refresh.
     */
    fun map(xml: String, now: Instant, region: String = REGION): List<Warning> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // The feed comes off the network, so it must never be able to name an external entity.
            // Android's parser supports neither of these two features and throws the feature's own
            // name back at you when asked for it — which is exactly how this failed on a phone while
            // passing on the JVM. They are attempted for the platforms that do honour them, and the
            // resolver below is what actually holds on Android.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder()
            // Every external entity resolves to nothing, on every platform.
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(ByteArrayInputStream(xml.toByteArray()))

        val entries = doc.getElementsByTagNameNS(ATOM, "entry")
        return (0 until entries.length).mapNotNull { i ->
            val entry = entries.item(i) as? Element ?: return@mapNotNull null
            if (geocode(entry) != region) return@mapNotNull null
            val event = entry.text(CAP, "event") ?: return@mapNotNull null
            val expires = entry.instant(CAP, "expires") ?: return@mapNotNull null
            if (now.isAfter(expires)) return@mapNotNull null
            val level = level(event, entry.text(CAP, "severity")) ?: return@mapNotNull null
            Warning(
                identifier = entry.text(CAP, "identifier") ?: entry.text(ATOM, "id") ?: return@mapNotNull null,
                type = type(event),
                level = level,
                areaDesc = entry.text(CAP, "areaDesc").orEmpty(),
                // Warnings issued for a period already under way carry no separate onset in practice,
                // but a missing one must not read as 1970: fall back to when the warning was sent.
                onset = entry.instant(CAP, "onset") ?: entry.instant(CAP, "effective") ?: entry.instant(CAP, "sent") ?: expires,
                expires = expires,
                headline = event,
            )
        }
            // Worst first, then soonest: the card shows the top one and the sheet the rest.
            .sortedWith(compareByDescending<Warning> { it.level }.thenBy { it.onset })
            .distinctBy { it.identifier }
    }

    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val CAP = "urn:oasis:names:tc:emergency:cap:1.2"

    /** `<cap:geocode><valueName>EMMA_ID</valueName><value>IT002</value></cap:geocode>` */
    private fun geocode(entry: Element): String? {
        val codes = entry.getElementsByTagNameNS(CAP, "geocode")
        return (0 until codes.length).firstNotNullOfOrNull { i ->
            val code = codes.item(i) as? Element ?: return@firstNotNullOfOrNull null
            if (code.text(ATOM, "valueName") != "EMMA_ID") null else code.text(ATOM, "value")
        }
    }

    private fun Element.text(ns: String, name: String): String? =
        getElementsByTagNameNS(ns, name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.instant(ns: String, name: String): Instant? =
        text(ns, name)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /**
     * Italy words its events as "Orange Rain Warning". The colour is the awareness level; a white
     * entry means "no special awareness required" and is not a warning at all, so it is dropped.
     * [severity] is CAP's own scale and stands in where the wording does not carry a colour.
     */
    internal fun level(event: String, severity: String?): WarningLevel? {
        val e = event.lowercase()
        return when {
            "red" in e -> WarningLevel.RED
            "orange" in e -> WarningLevel.ORANGE
            "yellow" in e -> WarningLevel.YELLOW
            "white" in e || "green" in e -> null
            else -> when (severity?.lowercase()) {
                "extreme" -> WarningLevel.RED
                "severe" -> WarningLevel.ORANGE
                "moderate" -> WarningLevel.YELLOW
                else -> null
            }
        }
    }

    /** MeteoAlarm's awareness types. Anything unrecognised stays visible as [WarningType.OTHER]. */
    internal fun type(event: String): WarningType {
        val e = event.lowercase()
        return when {
            "thunder" in e -> WarningType.THUNDERSTORM
            "rain-flood" in e || "rainflood" in e -> WarningType.RAIN_FLOOD
            "flood" in e -> WarningType.FLOOD
            "rain" in e -> WarningType.RAIN
            "snow" in e || "ice" in e -> WarningType.SNOW_ICE
            "wind" in e || "gale" in e || "storm" in e -> WarningType.WIND
            "fog" in e -> WarningType.FOG
            "high-temperature" in e || "heat" in e -> WarningType.HIGH_TEMPERATURE
            "low-temperature" in e || "cold" in e || "frost" in e -> WarningType.LOW_TEMPERATURE
            "coastal" in e -> WarningType.COASTAL_EVENT
            "forest" in e || "fire" in e -> WarningType.FOREST_FIRE
            "avalanche" in e -> WarningType.AVALANCHE
            else -> WarningType.OTHER
        }
    }
}
