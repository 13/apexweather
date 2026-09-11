package it.apexweather.data

import android.util.Log
import it.apexweather.data.local.BulletinEntity
import it.apexweather.data.local.ObservationEntity
import it.apexweather.data.local.RefreshMetaEntity
import it.apexweather.data.local.SourceForecastEntity
import it.apexweather.data.local.EnsembleEntity
import it.apexweather.data.local.StationHistoryDao
import it.apexweather.data.local.StationHistoryEntity
import it.apexweather.data.local.StationReferenceEntity
import it.apexweather.data.local.WarningsEntity
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.data.remote.EnsembleMapper
import it.apexweather.data.remote.EnsembleSpread
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
import it.apexweather.domain.BiasCorrector
import it.apexweather.domain.Place
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.StationSample
import it.apexweather.domain.SouthTyrol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    private val history: StationHistoryDao,
    private val openMeteo: OpenMeteoApi,
    private val geoSphere: GeoSphereApi,
    private val siag: SiagApi,
    private val odh: OdhApi,
    private val meteoAlarm: MeteoAlarmApi,
    private val ensembleApi: EnsembleApi,
    private val json: Json,
    private val clock: Clock,
) {
    /** [combine] is typed only up to five flows, so the two single-row caches arrive as one. */
    private data class Sidecars(
        val warnings: WarningsEntity?,
        val reference: StationReferenceEntity?,
        val history: List<StationHistoryEntity>,
        val ensemble: EnsembleEntity?,
    )

    fun snapshot(place: Place, language: String): Flow<WeatherSnapshot> {
        val historySince = clock.instant().minus(BiasCorrector.WINDOW).epochSecond
        val sidecars = combine(
            dao.warnings(), dao.stationReference(place.istat),
            history.history(place.istat, historySince), dao.ensemble(place.istat), ::Sidecars,
        )
        return combine(
            dao.forecasts(place.istat), dao.bulletin(place.district, language),
            dao.observation(place.istat), sidecars, dao.meta(place.istat),
        ) { rows, bulletinRow, obsRow, (warningRow, referenceRow, historyRows, ensembleRow), meta ->
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
            // A row whose hour has not happened yet holds forecasts and no thermometer reading, and
            // is nothing to learn from until it does.
            val samples = historyRows.mapNotNull { row ->
                val observed = row.observedC ?: return@mapNotNull null
                val byLead = decode("station history", LEAD_MODEL_TEMPS, row.modelsJson) ?: return@mapNotNull null
                StationSample(
                    time = Instant.ofEpochSecond(row.hourEpoch),
                    observedC = observed,
                    predictedC = byLead.mapNotNull { (leadName, models) ->
                        val lead = runCatching { LeadBucket.valueOf(leadName) }.getOrNull() ?: return@mapNotNull null
                        lead to models.mapNotNull { (name, temp) ->
                            runCatching { Source.valueOf(name) }.getOrNull()?.let { it to temp }
                        }.toMap()
                    }.toMap(),
                )
            }
            WeatherSnapshot(
                forecasts = forecasts,
                bulletin = bulletin,
                observation = observation,
                warnings = warnings.orEmpty().filter { it.isActiveAt(now) },
                stationReference = reference,
                // How wrong each model has lately been here, by part of the day; empty until enough
                // hours of a part have accumulated.
                modelBias = BiasCorrector.biases(samples, now, SouthTyrol.ZONE),
                ensemble = ensembleRow?.json?.let { decode("ensemble", EnsembleSpread.serializer(), it) },
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
     * Fetches every source for [place], or joins the fetch already running.
     *
     * There is more than one thing in this app entitled to ask for a refresh — the resume hook above
     * the tabs, the hourly worker, the widget's button — and on a first launch two of them arrive at
     * once: `WorkManager` runs a newly enqueued periodic job immediately, which lands on top of the
     * cold-start refresh. Measured on a fresh install, every single upstream was fetched exactly
     * twice. `StaleRefresher` has a flag of its own, but the worker does not go through it and could
     * not see it.
     *
     * So the coalescing lives here, where every caller passes. The second caller waits on the mutex
     * for the first to finish and is then handed its result, rather than starting the whole thing
     * again — which is both what it wanted and what it would have got.
     */
    suspend fun refresh(place: Place, language: String): RefreshResult = refreshMutex.withLock {
        val key = "${place.istat}|$language"
        val finishedAt = lastRefreshAt
        val previous = lastRefreshResult
        if (key == lastRefreshKey && previous != null && finishedAt != null &&
            Duration.between(finishedAt, clock.instant()) < COALESCE_WITHIN
        ) {
            return@withLock previous
        }
        val result = fetchEverything(place, language)
        lastRefreshKey = key
        lastRefreshAt = clock.instant()
        lastRefreshResult = result
        result
    }

    private val refreshMutex = Mutex()
    private var lastRefreshKey: String? = null
    private var lastRefreshAt: Instant? = null
    private var lastRefreshResult: RefreshResult? = null

    /**
     * Fetches every source in parallel; a failure in one never affects the others.
     *
     * Explicitly off the caller's dispatcher: the UI calls this from `viewModelScope`, i.e. the main
     * thread, and while Retrofit deserialises on its own thread, everything after each await resumes
     * on the caller's — the five Open-Meteo model mappings, and seven JSON encodings of ~168 hourly
     * points each. On the main thread that is visible jank on launch and on every pull to refresh.
     */
    private suspend fun fetchEverything(place: Place, language: String): RefreshResult = withContext(Dispatchers.Default) {
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
                    val mapped = attempt("OPEN_METEO") { OpenMeteoMapper.map(openMeteo.forecast(place.lat, place.lon), now) }
                    OpenMeteoMapper.MODELS.keys.forEach { source ->
                        storeForecast(place, source, mapped?.get(source), failed["OPEN_METEO"], now)
                    }
                }
            }
            val gs = async {
                isolate("GEOSPHERE_AROME") {
                    val fc = attempt("GEOSPHERE_AROME") { GeoSphereMapper.map(geoSphere.forecast("${place.lat},${place.lon}"), now) }
                    storeForecast(place, Source.GEOSPHERE_AROME, fc, failed["GEOSPHERE_AROME"], now)
                }
            }
            val km = async {
                isolate("SIAG_KMOS") {
                    val fc = attempt("SIAG_KMOS") { SiagMappers.mapKmos(siag.municipality(place.istat), now) }
                    storeForecast(place, Source.SIAG_KMOS, fc, failed["SIAG_KMOS"], now)
                }
            }
            val bl = async {
                isolate("SIAG_BULLETIN") {
                    val b = attempt("SIAG_BULLETIN") {
                        SiagMappers.mapBulletin(odh.weather(language), odh.district(place.district, language), language)
                    }
                    val prev = dao.bulletinOnce(place.district, language)
                    dao.upsertBulletin(
                        if (b != null) BulletinEntity(place.district, language, json.encodeToString(Bulletin.serializer(), b), now.toEpochMilli(), null, null)
                        else BulletinEntity(place.district, language, prev?.json, prev?.fetchedAtMs, failed["SIAG_BULLETIN"], now.toEpochMilli())
                    )
                }
            }
            // Both station halves only run where the place has a station near enough to speak for
            // it. A place without one is not a failure; it simply has nothing to measure.
            val ob = async {
                place.station?.let { station ->
                    isolate("SIAG_STATION") {
                        val o = attempt("SIAG_STATION") {
                            SiagMappers.mapObservation(siag.stations(), station) ?: error("station ${station.code} not in response")
                        }
                        val prev = dao.observationOnce(place.istat)
                        dao.upsertObservation(
                            if (o != null) ObservationEntity(place.istat, json.encodeToString(StationObservation.serializer(), o), now.toEpochMilli(), null, null)
                            else ObservationEntity(place.istat, prev?.json, prev?.fetchedAtMs, failed["SIAG_STATION"], now.toEpochMilli())
                        )
                    }
                }
            }
            val sr = async {
                place.station?.let { station ->
                    isolate("OPEN_METEO_STATION") {
                        val ref = attempt("OPEN_METEO_STATION") {
                            OpenMeteoStationMapper.map(openMeteo.stationForecast(station.lat, station.lon, station.altitudeM), now)
                        }
                        val prev = dao.stationReferenceOnce(place.istat)
                        dao.upsertStationReference(
                            if (ref != null) StationReferenceEntity(place.istat, json.encodeToString(StationReference.serializer(), ref), now.toEpochMilli(), null, null)
                            else StationReferenceEntity(place.istat, prev?.json, prev?.fetchedAtMs, failed["OPEN_METEO_STATION"], now.toEpochMilli())
                        )
                    }
                }
            }
            val en = async {
                isolate("ENSEMBLE") {
                    // Two ensembles, one row: ICON-D2 for the next two days and ECMWF's fifty
                    // members for the fortnight behind it. They are fetched together and counted as
                    // one source, because either on its own is still an ensemble — if ICON-D2 fails
                    // ECMWF covers the whole list at coarser resolution, and if ECMWF fails the far
                    // days simply go back to saying they are a single model.
                    val near = attempt("ENSEMBLE") {
                        EnsembleMapper.map(ensembleApi.forecast(place.lat, place.lon), now)
                    }
                    val far = attempt("ENSEMBLE_ECMWF") {
                        EnsembleMapper.map(
                            ensembleApi.forecast(
                                place.lat, place.lon,
                                models = EnsembleApi.ECMWF_ENS, forecastDays = EnsembleApi.ECMWF_ENS_DAYS,
                            ),
                            now,
                        )
                    }
                    val sp = EnsembleSpread.combine(near, far)
                    val prev = dao.ensembleOnce(place.istat)
                    dao.upsertEnsemble(
                        if (sp != null) EnsembleEntity(place.istat, json.encodeToString(EnsembleSpread.serializer(), sp), now.toEpochMilli(), null, null)
                        else EnsembleEntity(place.istat, prev?.json, prev?.fetchedAtMs, failed["ENSEMBLE"], now.toEpochMilli())
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
            om.await(); gs.await(); km.await(); bl.await(); ob.await(); wa.await(); sr.await(); en.await()
        }

        recordStationHour(place, now)

        val result = RefreshResult(succeeded.toList(), LinkedHashMap(failed))
        dao.upsertMeta(
            RefreshMetaEntity(
                place = place.istat,
                lastSuccessMs = if (succeeded.isNotEmpty()) now.toEpochMilli() else previousSuccessMs(place),
                lastAttemptMs = now.toEpochMilli(),
                lastAttemptFailed = result.allFailed,
            )
        )
        result
    }

    /**
     * Writes down what each model says about the hours ahead, and what the station actually read.
     *
     * Two halves that meet in the same row. The run just fetched is asked what it makes of this
     * hour, of six hours' time and of twelve — those are forecasts at three distances, written into
     * the rows of the hours they are about, which mostly have not happened yet. Separately, the
     * thermometer's reading for the hour it belongs to is written into that hour's row, which by
     * then is already holding what the models said about it half a day ago.
     *
     * That is the only way to have it. Nobody publishes what a model said yesterday about an hour
     * that has since happened, so the app has to have written it down before the hour arrived.
     *
     * Read back from the cache rather than threaded out of the fetches, because that is the version
     * that survived storage and because a refresh where one of the two failed simply has nothing to
     * record. Rows are merged rather than replaced: a refresh inside an hour must not wipe the
     * twelve-hour-old forecast that is the whole point of the row.
     */
    private suspend fun recordStationHour(place: Place, now: Instant) {
        val reference = dao.stationReferenceOnce(place.istat)?.json
            ?.let { decode("station reference", StationReference.serializer(), it) }
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)

        // What this run says about the hours still to come, filed under the hours it is about.
        if (reference != null) {
            listOf(LeadBucket.SIX, LeadBucket.TWELVE).forEach { lead ->
                val target = thisHour.plusSeconds(lead.hours * 3600)
                val models = reference.at(target).mapKeys { it.key.name }
                // A model whose run does not reach that far contributes nothing rather than a gap
                // that later reads as agreement.
                if (models.isNotEmpty()) mergeStationHour(place, target) { it + (lead.name to models) }
            }
        }

        // The reading, and the same run's word on the hour it belongs to — which is the station's
        // own hour, not the clock's: a reading taken at 13:40 is verified against what the models
        // said about 13:00, whatever time the refresh happens to run at.
        val observation = dao.observationOnce(place.istat)?.json
            ?.let { decode("observation", StationObservation.serializer(), it) }
        val observed = observation?.tempC
        if (observed != null) {
            val hour = observation.time.truncatedTo(ChronoUnit.HOURS)
            val atHour = reference?.at(hour)?.mapKeys { it.key.name }.orEmpty()
            mergeStationHour(place, hour, observed) {
                if (atHour.isEmpty()) it else it + (LeadBucket.NOW.name to atHour)
            }
        }
        history.prune(now.minus(BiasCorrector.WINDOW).epochSecond)
    }

    /**
     * Read, change, write one history row. [observed] is written where it is given and the stored
     * reading is kept where it is not, so the two halves above can arrive in either order.
     */
    private suspend fun mergeStationHour(
        place: Place,
        hour: Instant,
        observed: Double? = null,
        change: (Map<String, Map<String, Double>>) -> Map<String, Map<String, Double>>,
    ) {
        val existing = history.at(place.istat, hour.epochSecond)
        val stored = existing?.modelsJson?.let { decode("station history", LEAD_MODEL_TEMPS, it) }.orEmpty()
        history.upsert(
            StationHistoryEntity(
                place = place.istat,
                hourEpoch = hour.epochSecond,
                observedC = observed ?: existing?.observedC,
                modelsJson = json.encodeToString(LEAD_MODEL_TEMPS, change(stored)),
            ),
        )
    }

    /**
     * Drops every place but [keepPlaces], and every bulletin but those of [keepDistricts].
     *
     * Deliberately not part of [refresh]. A refresh takes seconds, and the reader can change place
     * inside those seconds; an eviction list computed before the network round-trip would then be
     * describing the place they have just left, and would delete the cache of the one they are now
     * looking at. Callers work this list out after the refresh returns, from settings read then.
     */
    suspend fun evictAllBut(keepPlaces: List<String>, keepDistricts: List<Int>) {
        if (keepPlaces.isEmpty() || keepDistricts.isEmpty()) return
        dao.evict(keepPlaces, keepDistricts)
        // The history lives in its own database now but is kept to the same places as the caches:
        // a place the reader has left is a place whose models nobody is checking any more.
        history.evict(keepPlaces)
    }

    private suspend fun previousSuccessMs(place: Place): Long? = dao.metaOnce(place.istat)?.lastSuccessMs

    private suspend fun storeForecast(place: Place, source: Source, fc: SourceForecast?, error: String?, now: Instant) {
        val prev = dao.forecastOnce(place.istat, source.name)
        dao.upsertForecast(
            if (fc != null) SourceForecastEntity(
                place = place.istat,
                source = source.name,
                json = json.encodeToString(SourceForecast.serializer(), fc),
                issuedAtMs = fc.issuedAt.toEpochMilli(),
                fetchedAtMs = now.toEpochMilli(),
                lastError = null,
                lastErrorAtMs = null,
            ) else SourceForecastEntity(
                place = place.istat,
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
        /**
         * How long a just-finished refresh answers for the next caller asking the same thing.
         *
         * Only long enough to absorb a duplicate that was already queued behind the first — the
         * second caller has usually been waiting on the mutex for the whole fetch, so by the time it
         * looks, no time has passed at all. Short enough that a reader who pulls to refresh, waits,
         * and pulls again gets a real fetch.
         */
        private val COALESCE_WITHIN: Duration = Duration.ofSeconds(10)

        /** One retry, not three: the worker runs again in an hour and nothing here is urgent. */
        private const val NETWORK_RETRIES = 1
        private const val RETRY_DELAY_MS = 3_000L

        private val WARNINGS: KSerializer<List<Warning>> = ListSerializer(Warning.serializer())
        /** Lead bucket name → source name → temperature, which is what a history row holds. */
        private val LEAD_MODEL_TEMPS: KSerializer<Map<String, Map<String, Double>>> =
            MapSerializer(String.serializer(), MapSerializer(String.serializer(), Double.serializer()))

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
