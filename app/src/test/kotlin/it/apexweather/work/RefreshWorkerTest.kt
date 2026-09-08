package it.apexweather.work

import androidx.work.ListenableWorker.Result
import it.apexweather.data.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkerTest {

    @Test
    fun `null result retries`() {
        assertEquals(Result.retry(), RefreshWorker.outcome(null, 0))
    }

    @Test
    fun `all failed retries on first attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.retry(), RefreshWorker.outcome(result, 0))
    }

    @Test
    fun `all failed retries on second attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.retry(), RefreshWorker.outcome(result, 1))
    }

    @Test
    fun `all failed fails on third attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.failure(), RefreshWorker.outcome(result, 2))
    }

    @Test
    fun `any success succeeds`() {
        val result = RefreshResult(listOf("OPEN_METEO"), emptyMap())
        assertEquals(Result.success(), RefreshWorker.outcome(result, 0))
    }
}
