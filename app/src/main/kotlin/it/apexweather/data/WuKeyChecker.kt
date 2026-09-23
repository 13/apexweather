package it.apexweather.data

import it.apexweather.data.remote.WeatherUndergroundApi
import retrofit2.HttpException
import javax.inject.Inject

/**
 * One deliberate test of the reader's key, run when they change it.
 *
 * `v3/location/near` at the place's own coordinates, because it proves the key end to end and is
 * the same call the stations screen makes — so the check spends nothing that was not going to be
 * spent anyway. One request against a cap of 1500 a day.
 *
 * Without this, a key is silent until something refreshes, and the thirty-minute staleness rule
 * puts that up to half an hour away. A wrong key and a key that has simply not been used yet then
 * look identical, which is exactly the confusion that cost an afternoon on 2026-09-23.
 */
class WuKeyChecker @Inject constructor(
    private val api: WeatherUndergroundApi,
    private val keySource: WuKeySource,
    private val reporter: WuKeyReporter,
) {
    suspend fun check(lat: Double, lon: Double) {
        val key = keySource.key()?.takeIf { it.isNotBlank() }
        if (key == null) {
            // Nothing to check, and no request may go out carrying an empty key.
            reporter.report(WuKeyVerdict.UNCHECKED)
            return
        }
        reporter.report(WuKeyVerdict.CHECKING)
        val verdict = try {
            api.near("$lat,$lon", key)
            WuKeyVerdict.GOOD
        } catch (e: HttpException) {
            WuKeyVerdicts.of(e.code())
        } catch (e: Exception) {
            WuKeyVerdicts.of(e)
        }
        reporter.report(verdict)
    }
}
