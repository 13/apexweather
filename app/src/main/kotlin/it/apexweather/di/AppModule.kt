package it.apexweather.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.apexweather.BuildConfig
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.HistoryDatabase
import it.apexweather.data.local.StationHistoryDao
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.MeteoAlarmApi
import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.SouthTyrol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /**
     * How much disk the HTTP cache may use.
     *
     * It exists for one call in particular. SIAG's station list sends `ETag` and `max-age=600` and
     * is about 80 kB uncompressed (measured 2026-09-16), and the foreground loop asks for it every
     * ten minutes — so inside those ten minutes OkHttp answers from disk with no network call at
     * all, and past them a conditional request costs a `304` with no body. That is what makes a
     * ten-minute poll affordable, and it is why the cadence is ten and not five.
     *
     * Five megabytes because the largest thing that could land in it is a forecast payload of tens
     * of kilobytes; this is room to spare rather than a budget.
     */
    private const val HTTP_CACHE_BYTES = 5L * 1024 * 1024

    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // A whole-call bound as well as the per-phase ones: a server that dribbles a byte every ten
        // seconds satisfies the read timeout forever, and the refresh waits on it forever with it.
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "ApexWeather/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton fun openMeteo(c: OkHttpClient, j: Json): OpenMeteoApi = retrofit(OpenMeteoApi.BASE_URL, c, j).create(OpenMeteoApi::class.java)
    @Provides @Singleton fun geoSphere(c: OkHttpClient, j: Json): GeoSphereApi = retrofit(GeoSphereApi.BASE_URL, c, j).create(GeoSphereApi::class.java)
    @Provides @Singleton fun siag(c: OkHttpClient, j: Json): SiagApi = retrofit(SiagApi.BASE_URL, c, j).create(SiagApi::class.java)
    @Provides @Singleton fun odh(c: OkHttpClient, j: Json): OdhApi = retrofit(OdhApi.BASE_URL, c, j).create(OdhApi::class.java)
    // Returns the Atom feed as a raw body: Retrofit hands ResponseBody back without a converter,
    // and MeteoAlarmMapper does the XML parsing.
    @Provides @Singleton fun ensemble(c: OkHttpClient, j: Json): EnsembleApi = retrofit(EnsembleApi.BASE_URL, c, j).create(EnsembleApi::class.java)
    @Provides @Singleton fun meteoAlarm(c: OkHttpClient, j: Json): MeteoAlarmApi = retrofit(MeteoAlarmApi.BASE_URL, c, j).create(MeteoAlarmApi::class.java)
    @Provides @Singleton fun rainViewer(c: OkHttpClient, j: Json): RainViewerApi = retrofit(RainViewerApi.BASE_URL, c, j).create(RainViewerApi::class.java)
    @Provides @Singleton fun tileDecoder(): it.apexweather.data.TileDecoder = it.apexweather.data.AndroidTileDecoder
    @Provides fun meteredNetwork(@ApplicationContext context: Context): it.apexweather.data.MeteredNetwork =
        it.apexweather.data.MeteredNetwork {
            // No connectivity service to ask is not a licence to spend somebody's data.
            context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        }
    @Provides fun radarNow(radar: it.apexweather.data.RadarRepository): it.apexweather.data.RadarNowSource = radar
    @Provides @Singleton fun nowcast(c: OkHttpClient, j: Json): NowcastApi = retrofit(NowcastApi.BASE_URL, c, j).create(NowcastApi::class.java)
    // Returns the NetCDF as a raw body, like the Atom feed above; NowcastGrid reads it.
    @Provides @Singleton fun nowcastSource(api: NowcastApi): it.apexweather.data.NowcastSource = it.apexweather.data.GeoSphereNowcastSource(api)
    // Five seconds for the whole call, not the forecast client's 45: this answers a sheet the reader
    // is looking at, and "not available" beats a spinner that outlasts their interest.
    @Provides @Singleton fun sourceMeta(c: OkHttpClient, j: Json): SourceMetaApi =
        retrofit(SourceMetaApi.BASE_URL, c.newBuilder().callTimeout(5, TimeUnit.SECONDS).build(), j).create(SourceMetaApi::class.java)

    @Provides @Singleton fun database(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.build(ctx)
    @Provides fun dao(db: AppDatabase): WeatherDao = db.weatherDao()
    @Provides @Singleton fun historyDatabase(@ApplicationContext ctx: Context): HistoryDatabase = HistoryDatabase.build(ctx)
    @Provides fun historyDao(db: HistoryDatabase): StationHistoryDao = db.stationHistoryDao()
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton fun blender(): ConsensusBlender = ConsensusBlender(SouthTyrol.ZONE)
}
