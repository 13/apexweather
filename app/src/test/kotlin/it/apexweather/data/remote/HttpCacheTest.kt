package it.apexweather.data.remote

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The disk cache, which is what makes polling the station every ten minutes affordable.
 *
 * SIAG's station list sends `ETag` and `max-age=600` and is about 80 kB uncompressed; inside those
 * ten minutes the answer comes off disk with no network call at all, and past them a conditional
 * request costs a `304` with no body. Asserting that a `Cache` object exists would prove none of
 * that, so this drives a real server and counts the requests it actually receives.
 */
class HttpCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder("http"), 5L * 1024 * 1024))
            .build()
    }

    @After fun tearDown() {
        server.close()
        client.cache?.close()
    }

    private fun get(): String? =
        client.newCall(Request.Builder().url(server.url("/stations")).build()).execute()
            .use { it.body?.string() }

    /** Inside the freshness the server itself declared, nothing goes out at all. */
    @Test
    fun `a fresh response is served from disk without a request`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Cache-Control", "max-age=600")
                .addHeader("ETag", "\"v1\"")
                .body("stations")
                .build(),
        )

        assertEquals("stations", get())
        assertEquals("stations", get())

        assertEquals("the second read should not have gone out", 1, server.requestCount)
    }

    /** Past it, the conditional request costs a 304 and no body. */
    @Test
    fun `a stale response is revalidated and the body is not sent again`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("ETag", "\"v1\"")
                .body("stations")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(304).build())

        assertEquals("stations", get())
        assertEquals("the cached body should have been reused", "stations", get())

        assertEquals(2, server.requestCount)
        server.takeRequest()
        val conditional = server.takeRequest()!!
        assertNotNull("it did not revalidate", conditional.headers["If-None-Match"])
        assertEquals(1, client.cache!!.hitCount())
    }

    /** A response with nothing to validate against is simply fetched again. */
    @Test
    fun `a response with no validator is refetched`() {
        repeat(2) { server.enqueue(MockResponse.Builder().body("stations").build()) }

        get()
        get()

        assertEquals(2, server.requestCount)
    }
}
