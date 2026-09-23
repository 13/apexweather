package it.apexweather.data

import it.apexweather.data.remote.WeatherUndergroundApi
import it.apexweather.data.remote.WuNearLocation
import it.apexweather.data.remote.WuNearResponse
import it.apexweather.data.remote.WuResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

private class FakeWu(private val onNear: () -> WuNearResponse) : WeatherUndergroundApi {
    override suspend fun current(stationId: String, apiKey: String): Response<WuResponse> =
        Response.success(WuResponse())

    override suspend fun near(geocode: String, apiKey: String): WuNearResponse = onNear()
}

class WuKeyCheckerTest {
    private val reported = mutableListOf<WuKeyVerdict>()
    private val reporter = WuKeyReporter { reported += it }

    private fun checker(key: String?, onNear: () -> WuNearResponse) = WuKeyChecker(
        api = FakeWu(onNear),
        keySource = { key },
        reporter = reporter,
    )

    private fun httpError(code: Int): Throwable = retrofit2.HttpException(
        Response.error<WuNearResponse>(code, "".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun anAnsweredCallReportsGood() = runTest {
        checker("k") { WuNearResponse(WuNearLocation(stationId = listOf("ITIROL16"))) }
            .check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.GOOD), reported)
    }

    @Test
    fun aRefusedKeyIsReportedAsRefused() = runTest {
        checker("k") { throw httpError(401) }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.REFUSED), reported)
    }

    @Test
    fun anExhaustedQuotaIsReportedAsSuch() = runTest {
        checker("k") { throw httpError(429) }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.OVER_QUOTA), reported)
    }

    @Test
    fun aDroppedConnectionAccusesNobody() = runTest {
        checker("k") { throw IOException("reset") }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.OFFLINE), reported)
    }

    /** No key is nothing to check, and no request may go out for one. */
    @Test
    fun withNoKeyNothingIsCheckedAndNothingIsReported() = runTest {
        var called = false
        checker(null) { called = true; WuNearResponse() }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.UNCHECKED), reported)
        assertEquals(false, called)
    }

    @Test
    fun aBlankKeyIsTreatedAsNoKey() = runTest {
        checker("   ") { WuNearResponse() }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.UNCHECKED), reported)
    }
}
