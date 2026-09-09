package it.apexweather.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    private fun v(s: String) = checkNotNull(AppVersion.parse(s)) { "expected $s to parse" }

    @Test fun `reads a plain version and a tag`() {
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("0.2.0"))
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("v0.2.0"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse(" v1 "))
        assertEquals(AppVersion(1, 2, 0), AppVersion.parse("1.2"))
    }

    /** The whole reason this compares three numbers instead of strings. */
    @Test fun `ten is newer than nine`() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.1") > v("0.2.0"))
    }

    @Test fun `older and equal are not offered as updates`() {
        assertTrue(v("0.1.0") < v("0.2.0"))
        assertEquals(0, v("0.2.0").compareTo(v("v0.2.0")))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    /**
     * An unreadable tag must be reported as "cannot tell". Returning a version here would let a
     * pre-release or a typo be compared as though it were a real number.
     */
    @Test fun `anything that is not three numbers is unreadable`() {
        listOf(null, "", "v", "nightly", "1.0.0-rc1", "1.2.3.4", "1..2", "-1.0.0", "1.0.0+build")
            .forEach { assertNull("expected '$it' to be unreadable", AppVersion.parse(it)) }
    }

    @Test fun `prints without the tag prefix`() = assertEquals("0.2.0", v("v0.2.0").toString())
}
