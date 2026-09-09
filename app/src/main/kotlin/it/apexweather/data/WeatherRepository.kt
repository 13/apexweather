package it.apexweather.data

import android.util.Log
import it.apexweather.data.local.BulletinEntity
import it.apexweather.data.local.ObservationEntity
import it.apexweather.data.local.RefreshMetaEntity
import it.apexweather.data.local.SourceForecastEntity
import it.apexweather.data.local.StationReferenceEntity
import it.apexweather.data.local.WarningsEntity
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereMapper
import it.apexweather.data.remote.MeteoAlarmApi
import it.apexweather.data.remote.MeteoAlarmMapper
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.StationReference
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoMapper
import it.apexweather.data.remote.OpenMeteoStationMapper
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagMappers
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.DorfTirol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshResult(val succeeded: List<String>, val failed: Map<String, String>) {
    val allFailed: Boolean get() = succeeded.isEmpty() && failed.isNotEmpty()
}

@Singleton
class WeatherRepository @Inject constructor(
    private val dao: WeatherDao,
    private val openMeteo: OpenMeteoApi,
    private val geoSphere: GeoSphereApi,
    private val siag: SiagApi,
    private val odh: OdhApi,
    private val meteoAlarm: MeteoAlarmApi,
    private val json: Json,
    private val clock: Clock,
) {
    /** [combine] is typed only up to five flows, so the two single-row caches arrive as one. */
    private data class Sidecars(val warnings: WarningsEntity?, val reference: StationReferenceEntity?)

    fun snapshot(language: String): Flow<WeatherSnapshot> {
        val sidecars = combine(dao.warnings(), dao.stationReference(), ::Sidecars)
        return combine(dao.forecasts(), dao.bulletin(language), dao.observation(), sidecars, dao.meta()) { rows, bulletinRow, obsRow, (warningRow, referenceRow), meta ->
            val now = clock.instant()
            val forecasts = mutableMapOf<Source, SourceForecast>()
            val status = mutableMapOf<Source, SourceStatus>()
            rows.forEach { row ->
                val source = runCatching { Source.valueOf(row.source) }.getOrNull() ?: return@forEach
                val fc = row.json?.let { decode("forecast for ${row.source}", SourceForecast.serializer(), it) }
                if (fc != null) forecasts[source] = fc
                // A row whose JSON no longer decodes still knows when it was issued; keep that timestamp visible.
                status[source] = statusOf(
                    hasData = fc != null,
                    issuedAt = fc?.issuedAt ?: row.issuedAtMs?.let(Instant::ofEpochMilli),
                    fetchedAtMs = row.fetchedAtMs,
                    lastError = row.lastError, lastErrorAtMs = row.lastErrorAtMs,
                    staleAfter = Duration.ofHours(source.staleAfterHours.toLong()), now = now,
                )
            }
            val bulletin = bulletinRow?.json?.let { decode("bulletin", Bulletin.serializer(), it) }
            val observation = obsRow?.json?.let { decode("observation", StationObservation.serializer(), it) }
            // A cached warning outlives the refresh that fetched it, so it is filtered here rather
            // than at fetch time: one that expired since is gone from the app the minute it expires.
            val warnings = warningRow?.json?.let { decode("warnings", WARNINGS, it) }
            val reference = referenceRow?.json?.let { decode("station reference", StationReference.serializer(), it) }
            WeatherSnapshot(
                forecasts = forecasts,
                bulletin = bulletin,
                observation = observation,
                warnings = warnings.orEmpty().filter { it.isActiveAt(now) },
                stationReference = reference,
                status = status,
                bulletinStatus = bulletinRow?.let {
                    statusOf(bulletin != null, bulletin?.issuedAt, it.fetchedAtMs, it.lastError, it.lastErrorAtMs, Duration.ofHours(24), now)
                },
                observationStatus = obsRow?.let {
                    statusOf(observation != null, observation?.time, it.fetchedAtMs, it.lastError, it.lastErrorAtMs, Duration.ofMinutes(90), now)
                },
                // The feed carries no issue time of its own, so the fetch is the only timestamp there is.
                warningStatus = warningRow?.let {
                    statusOf(warnings != null, it.fetchedAtMs?.let(Instant::ofEpochMilli), it.fetchedAtMs, it.lastError, it.lastErrorAtMs, WARNINGS_STALE_AFTER, now)
                },
                lastSuccessfulRefresh = meta?.lastSuccessMs?.let(Instant::ofEpochMilli),
                lastRefreshFailed = meta?.lastAttemptFailed ?: false,
            )
        }
    }

    /** Cached JSON can outlive a model change; a row that no longer decodes is reported, never fatal. */
    private fun <T> decode(what: String, serializer: KSerializer<T>, text: String): T? =
        try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            Log.w("WeatherRepository", "cannot decode cached $what", e)
            null
        }

    /**
     * Fetches every source in parallel; a failure in one never affects the others.
     *
     * Explicitly off the caller's dispatcher: the UI calls this from `viewModelScope`, i.e. the main
     * thread, and while Retrofit deserialises on its own thread, everything after each await resumes
     * on the caller's — the five Open-Meteo model mappings, and seven JSON encodings of ~168 hourly
     * points each. On the main thread that is visible jank on launch and on every pull to refresh.
     */
    suspend fun refresh(language: String): RefreshResult = withContext(Dispatchers.Default) {
        val now = clock.instant()
        // The blocks below run in parallel on whatever threads the network continuations resume on,
        // so the shared bookkeeping has to be synchronised.
        val succeeded = Collections.synchronizedList(mutableListOf<String>())
        val failed = Collections.synchronizedMap(linkedMapOf<String, String>())

        /**
         * One attempt, then one retry if the network was the problem.
         *
         * These calls go out over a phone's connection, often the moment it wakes for the hourly
         * worker and before the radio has settled, and a single dropped connection used to cost a
         * whole source for an hour — the next refresh is not until then. A second try a few seconds
         * later costs almost nothing and recovers exactly that case. Only IOException is retried: a
         * malformed payload or a 404 will fail the same way twice.
         *
         * Cancellation (WorkManager stopping the worker) must propagate: a cancelled refresh is not
         * a failed one, and must not be retried either.
         */
        suspend fun <T> attempt(name: String, block: suspend () -> T): T? {
            var attemptsLeft = 1 + NETWORK_RETRIES
            while (true) {
                attemptsLeft--
                try {
                    val value = block()
                    succeeded += name
                    return value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attemptsLeft > 0 && e is IOException) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    failed[name] = e.message ?: e.javaClass.simpleName
                    return null
                }
            }
        }

        // Persistence is fallible too (Room, serialisation). One source failing to store must not abort the
        // others via await(), and must not skip the meta row, so nothing escapes an async body but cancellation.
        suspend fun isolate(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[name] = "store: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        supervisorScope {
            val om = async {
                isolate("OPEN_METEO") {
                    val mapped = attempt("OPEN_METEO") { OpenMeteoMapper.map(openMeteo.forecast(), now) }
                    OpenMeteoMapper.MODELS.keys.forEach { source ->
                        storeForecast(source, mapped?.get(source), failed["OPEN_METEO"], now)
                    }
                }
            }
            val gs = async {
                isolate("GEOSPHERE_AROME") {
                    val fc = attempt("GEOSPHERE_AROME") { GeoSphereMapper.map(geoSphere.forecast(), now) }
                    storeForecast(Source.GEOSPHERE_AROME, fc, failed["GEOSPHERE_AROME"], now)
                }
            }
            val km = async {
                isolate("SIAG_KMOS") {
                    val fc = attempt("SIAG_KMOS") { SiagMappers.mapKmos(siag.municipality(), now) }
                    storeForecast(Source.SIAG_KMOS, fc, failed["SIAG_KMOS"], now)
                }
            }
            val bl = async {
                isolate("SIAG_BULLETIN") {
                    val b = attempt("SIAG_BULLETIN") { SiagMappers.mapBulletin(odh.weather(language), odh.district(language = language), language) }
                    val prev = dao.bulletinOnce(language)
                    dao.upsertBulletin(
                        if (b != null) BulletinEntity(language, json.encodeToString(Bulletin.serializer(), b), now.toEpochMilli(), null, null)
                        else BulletinEntity(language, prev?.json, prev?.fetchedAtMs, failed["SIAG_BULLETIN"], now.toEpochMilli())
                    )
                }
            }
            val ob = async {
                isolate("SIAG_STATION") {
                    val o = attempt("SIAG_STATION") { SiagMappers.mapObservation(siag.stations()) ?: error("station ${DorfTirol.STATION_CODE} not in response") }
                    val prev = dao.observationOnce()
                    dao.upsertObservation(
                        if (o != null) ObservationEntity(0, json.encodeToString(StationObservation.serializer(), o), now.toEpochMilli(), null, null)
                        else ObservationEntity(0, prev?.json, prev?.fetchedAtMs, failed["SIAG_STATION"], now.toEpochMilli())
                    )
                }
            }
            val sr = async {
                isolate("OPEN_METEO_STATION") {
                    val ref = attempt("OPEN_METEO_STATION") { OpenMeteoStationMapper.map(openMeteo.stationForecast(), now) }
                    val prev = dao.stationReferenceOnce()
                    dao.upsertStationReference(
                        if (ref != null) StationReferenceEntity(0, json.encodeToString(StationReference.serializer(), ref), now.toEpochMilli(), null, null)
                        else StationReferenceEntity(0, prev?.json, prev?.fetchedAtMs, failed["OPEN_METEO_STATION"], now.toEpochMilli())
                    )
                }
            }
            val wa = async {
                isolate("METEOALARM") {
                    val w = attempt("METEOALARM") { MeteoAlarmMapper.map(meteoAlarm.italy().string(), now) }
                    val prev = dao.warningsOnce()
                    dao.upsertWarnings(
                        if (w != null) WarningsEntity(0, json.encodeToString(WARNINGS, w), now.toEpochMilli(), null, null)
                        else WarningsEntity(0, prev?.json, prev?.fetchedAtMs, failed["METEOALARM"], now.toEpochMilli())
                    )
                }
            }
            om.await(); gs.await(); km.await(); bl.await(); ob.await(); wa.await(); sr.await()
        }

        val result = RefreshResult(succeeded.toList(), LinkedHashMap(failed))
        dao.upsertMeta(
            RefreshMetaEntity(
                lastSuccessMs = if (succeeded.isNotEmpty()) now.toEpochMilli() else previousSuccessMs(),
                lastAttemptMs = now.toEpochMilli(),
                lastAttemptFailed = result.allFailed,
            )
        )
        result
    }

    private suspend fun previousSuccessMs(): Long? = dao.meta().first()?.lastSuccessMs

    private suspend fun storeForecast(source: Source, fc: SourceForecast?, error: String?, now: Instant) {
        val prev = dao.forecastOnce(source.name)
        dao.upsertForecast(
            if (fc != null) SourceForecastEntity(
                source = source.name,
                json = json.encodeToString(SourceForecast.serializer(), fc),
                issuedAtMs = fc.issuedAt.toEpochMilli(),
                fetchedAtMs = now.toEpochMilli(),
                lastError = null,
                lastErrorAtMs = null,
            ) else SourceForecastEntity(
                source = source.name,
                json = prev?.json,
                issuedAtMs = prev?.issuedAtMs,
                fetchedAtMs = prev?.fetchedAtMs,
                lastError = error ?: "unknown error",
                lastErrorAtMs = now.toEpochMilli(),
            )
        )
    }

    companion object {
        /** One retry, not three: the worker runs again in an hour and nothing here is urgent. */
        private const val NETWORK_RETRIES = 1
        private const val RETRY_DELAY_MS = 3_000L

        private val WARNINGS: KSerializer<List<Warning>> = ListSerializer(Warning.serializer())

        /** MeteoAlarm publishes a few times a day; six hours without one means we are behind. */
        private val WARNINGS_STALE_AFTER: Duration = Duration.ofHours(6)

        fun statusOf(
            hasData: Boolean, issuedAt: Instant?, fetchedAtMs: Long?,
            lastError: String?, lastErrorAtMs: Long?, staleAfter: Duration, now: Instant,
        ): SourceStatus {
            if (!hasData) return SourceStatus.Failed(lastError ?: "no data", issuedAt)
            // >= so that a failure recorded in the same millisecond as the fetch still counts as a failure.
            val failedAfterFetch = lastErrorAtMs != null && lastErrorAtMs >= (fetchedAtMs ?: 0L)
            if (failedAfterFetch) return SourceStatus.Failed(lastError ?: "error", issuedAt)
            val issued = issuedAt ?: return SourceStatus.Failed("no timestamp", null)
            return if (Duration.between(issued, now) > staleAfter) SourceStatus.Stale(issued) else SourceStatus.Ok(issued)
        }
    }
}
