package it.apexweather.diagnostics

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetworkLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val client = OkHttpClient.Builder().addInterceptor(NetworkLog()).build()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = tmp.newFolder()
        AppLog.installAt(dir)
    }

    @After fun tearDown() {
        AppLog.uninstall()
        server.close()
    }

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
    private fun log(): String = File(dir, LogFile.NAME).takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun `a failed call is logged with its status and without its query`() {
        server.enqueue(MockResponse.Builder().code(400).body("bad").build())
        get("/v1/timeseries/forecast/nwp-v1-1h-2500m?lat_lon=46.6,11.1&apiKey=secret")
        AppLog.flushBlocking()
        val text = log()
        assertTrue(text, text.contains("W Net: GET ${server.hostName}/v1/timeseries/forecast/nwp-v1-1h-2500m → 400 in"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("lat_lon"))
    }

    @Test
    fun `a radar tile that arrives is not worth a line, one that fails is`() {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body("png").build())
        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "image/png").body("png").build())
        get("/v2/radar/1/256/7/68/45/2/1_1.png")
        get("/v2/radar/2/256/7/68/45/2/1_1.png")
        AppLog.flushBlocking()
        val text = log()
        assertFalse(text, text.contains("/v2/radar/1/"))
        assertTrue(text, text.contains("/v2/radar/2/256/7/68/45/2/1_1.png → 404"))
    }
}
