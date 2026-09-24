package it.apexweather.diagnostics

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * One log line per request that went through the shared client: which host and path, the status,
 * how long it took, how big it was, and whether OkHttp's cache answered.
 *
 * This is where "AROME answered HTTP 400 for months" would have been one look at a file rather than
 * a discovery. Every upstream passes this one client, the updater's download included.
 *
 * Successful image fetches are not logged: the radar asks for four tiles a frame, thirteen frames a
 * loop, and at that rate they would push yesterday out of a 256 kB log in a quarter of an hour.
 * A failed one still is. The query string is dropped entirely — the path says which call it was,
 * and the query is where the Weather Underground key travels.
 */
class NetworkLog(private val clock: () -> Long = System::nanoTime) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${request.url.host}${request.url.encodedPath}"
        val started = clock()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            AppLog.w(TAG, "$what failed after ${elapsedMs(started)} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val ms = elapsedMs(started)
        val image = response.header("Content-Type")?.startsWith("image/") == true
        if (response.isSuccessful && image) return response
        val cached = when {
            response.cacheResponse != null && response.networkResponse == null -> ", cache"
            response.cacheResponse != null -> ", revalidated"
            else -> ""
        }
        val size = response.body.contentLength().takeIf { it >= 0 }?.let { ", ${it / 1024} kB" } ?: ""
        val line = "$what → ${response.code} in $ms ms$size$cached"
        if (response.isSuccessful || response.code == 304) AppLog.i(TAG, line) else AppLog.w(TAG, line)
        return response
    }

    private fun elapsedMs(started: Long) = (clock() - started) / 1_000_000

    private companion object {
        const val TAG = "Net"
    }
}
