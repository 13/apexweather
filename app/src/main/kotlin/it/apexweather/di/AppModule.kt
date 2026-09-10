package it.apexweather.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.apexweather.BuildConfig
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.MeteoAlarmApi
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.SiagApi
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.SouthTyrol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
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

    @Provides @Singleton fun database(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.build(ctx)
    @Provides fun dao(db: AppDatabase): WeatherDao = db.weatherDao()
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton fun blender(): ConsensusBlender = ConsensusBlender(SouthTyrol.ZONE)
}
