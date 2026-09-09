package it.apexweather.update

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The client used for the APK download, which needs different timeouts from the weather calls. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateDownloads

/**
 * Bindings for the update package only, kept out of the app's own module so the whole feature
 * stays removable in one piece.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides @Singleton
    fun gitHubApi(client: OkHttpClient, json: Json): GitHubApi = Retrofit.Builder()
        .baseUrl(GitHubApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)

    /**
     * The shared client's fifteen-second read timeout is right for a weather response and wrong
     * for a five-megabyte APK on a weak connection. Derived from it rather than built fresh, so
     * the connection pool and the User-Agent header are still shared.
     */
    @Provides @Singleton @UpdateDownloads
    fun downloadClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()
}
