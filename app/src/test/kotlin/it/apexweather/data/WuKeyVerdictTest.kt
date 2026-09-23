package it.apexweather.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class WuKeyVerdictTest {
    @Test
    fun twoHundredIsGood() {
        assertEquals(WuKeyVerdict.GOOD, WuKeyVerdicts.of(200))
    }

    /**
     * 204 is a live station with nothing to report in the last hour. It proves the key worked — an
     * unauthorised request never gets that far.
     */
    @Test
    fun noContentIsAlsoGood() {
        assertEquals(WuKeyVerdict.GOOD, WuKeyVerdicts.of(204))
    }

    @Test
    fun unauthorisedAndForbiddenAreRefusals() {
        assertEquals(WuKeyVerdict.REFUSED, WuKeyVerdicts.of(401))
        assertEquals(WuKeyVerdict.REFUSED, WuKeyVerdicts.of(403))
    }

    @Test
    fun tooManyRequestsIsTheQuota() {
        assertEquals(WuKeyVerdict.OVER_QUOTA, WuKeyVerdicts.of(429))
    }

    /**
     * A server fault says nothing about the key. Reporting "refused" for a 503 would send the
     * reader off to re-type a key that is perfectly good.
     */
    @Test
    fun aServerFaultDoesNotAccuseTheKey() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(500))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(503))
    }

    @Test
    fun aTransportFailureIsNoConnection() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(UnknownHostException("api.weather.com")))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(SocketTimeoutException("timeout")))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(IOException("reset")))
    }

    /** A parse failure is the app's fault or the upstream's, and again not the key's. */
    @Test
    fun anythingElseIsAlsoNoConnectionRatherThanARefusal() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(IllegalStateException("bad json")))
    }
}
