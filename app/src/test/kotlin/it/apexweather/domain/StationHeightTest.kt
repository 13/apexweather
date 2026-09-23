package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationHeightTest {
    /** The DEM wins outright: it is ground that was measured, not a number somebody typed. */
    @Test
    fun theGroundIsTheHeightEvenWhereTheClaimAgrees() {
        assertEquals(639, StationHeight.heightOf(claimedM = 634, demM = 639))
    }

    @Test
    fun theGroundIsTheHeightWhereTheClaimIsAbsurd() {
        assertEquals(631, StationHeight.heightOf(claimedM = 182, demM = 631))
    }

    /** No DEM, no height the app will act on: nothing may be chosen. */
    @Test
    fun withoutTheGroundThereIsNoHeight() {
        assertNull(StationHeight.heightOf(claimedM = 182, demM = null))
        assertNull(StationHeight.heightOf(claimedM = null, demM = null))
    }

    /** A station that claims nothing disputes nothing; it merely said nothing. */
    @Test
    fun aStationThatClaimsNothingIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = null, demM = 631))
    }

    @Test
    fun aClaimWithinTheGateIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = 669, demM = 659))
        assertFalse(StationHeight.disputed(claimedM = 559, demM = 659))
    }

    /** ITIROL26 as Weather Underground had it: 669 ft read as metres against real ground at 654. */
    @Test
    fun theFeetForMetresMistakeIsDisputed() {
        assertTrue(StationHeight.disputed(claimedM = 204, demM = 654))
    }

    /** Exactly the gate is inside it; the generator drops only what is strictly further out. */
    @Test
    fun theGateItselfIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = 559, demM = 659))
        assertTrue(StationHeight.disputed(claimedM = 558, demM = 659))
    }
}
