package it.apexweather.data

import it.apexweather.data.remote.SourceMeta
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.data.remote.SourceMetaMapper
import it.apexweather.domain.Delivery
import it.apexweather.domain.SourceInfo
import it.apexweather.domain.model.Source
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A model's latest run as its provider publishes it, for the source sheet.
 *
 * Held in memory for ten minutes — the radar's rule — and never written to Room: it describes the
 * provider right now, and an old answer is worth less than asking again. A failure is null and is
 * not remembered, so the next open of the sheet tries again. Cancellation is passed on: a sheet
 * closed mid-fetch stops the fetch.
 */
@Singleton
class SourceMetaRepository @Inject constructor(
    private val api: SourceMetaApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val held = HashMap<Source, Pair<Instant, SourceMeta>>()

    suspend fun metaFor(source: Source): SourceMeta? {
        val url = urlFor(source) ?: return null
        mutex.withLock {
            held[source]?.let { (at, meta) -> if (Duration.between(at, clock.instant()) < FRESH_FOR) return meta }
        }
        val meta = runCatchingCancellable {
            val json = api.metadata(url)
            if (SourceInfo.of(source).delivery == Delivery.GEOSPHERE) SourceMetaMapper.geoSphere(json) else SourceMetaMapper.openMeteo(json)
        }.getOrNull() ?: return null
        mutex.withLock { held[source] = clock.instant() to meta }
        return meta
    }

    companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(10)

        /** Null where the provider publishes nothing to ask: KMOS's status time is already its run. */
        fun urlFor(source: Source): String? {
            val info = SourceInfo.of(source)
            return when (info.delivery) {
                Delivery.OPEN_METEO -> info.metaDataset?.let(SourceMetaApi::openMeteoUrl)
                Delivery.GEOSPHERE -> SourceMetaApi.GEOSPHERE_AROME_URL
                Delivery.SIAG -> null
            }
        }
    }
}
