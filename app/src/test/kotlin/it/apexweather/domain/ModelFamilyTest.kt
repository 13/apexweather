package it.apexweather.domain

import it.apexweather.domain.model.ModelFamily
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * What the family weighting is for, stated as arithmetic.
 *
 * The consensus is a median and a median counts votes, so four runs of one dynamical core decided
 * every close hour on their own. These pin the three things that must stay true of the fix: it
 * changes nothing when every model is its own family, it never de-duplicates a family down to one
 * model, and it groups by core rather than by letterhead.
 */
class ModelFamilyTest {

    @Test
    fun `the four ICON runs are one family and the two ECMWF runs are not`() {
        assertEquals(
            listOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2),
            Source.entries.filter { it.family == ModelFamily.ICON },
        )
        assertEquals(
            listOf(Source.KNMI_HARMONIE, Source.DMI_HARMONIE),
            Source.entries.filter { it.family == ModelFamily.HARMONIE },
        )
        // IFS solves equations and AIFS is machine-learned; the whole reason AIFS is on the list is
        // that it fails differently. Sharing an institution is not sharing a core.
        assertTrue(Source.ECMWF.family != Source.ECMWF_AIFS.family)
    }

    @Test
    fun `a model with no kin present weighs one`() {
        val alone = setOf(Source.GEOSPHERE_AROME, Source.SIAG_KMOS, Source.ECMWF)
        alone.forEach { assertEquals(1.0, ConsensusBlender.weightOf(it, alone), 1e-9) }
    }

    /**
     * 1/sqrt(n), not 1/n. Full de-duplication would say the four ICONs are one model, which is false
     * in a way that matters — ICON-CH1 runs at 1 km over this terrain and ICON-D2 at 2 km over a
     * different domain. The middle is deliberate; see [ConsensusBlender.weightOf].
     */
    @Test
    fun `a family of four is worth two votes, not one and not four`() {
        val regional = Source.entries.filter { it.regional }.toSet()
        val icon = regional.filter { it.family == ModelFamily.ICON }
        assertEquals(4, icon.size)
        icon.forEach { assertEquals(0.5, ConsensusBlender.weightOf(it, regional), 1e-9) }
        assertEquals(2.0, icon.sumOf { ConsensusBlender.weightOf(it, regional) }, 1e-9)

        // What that is worth as a share of the regional eight: half the vote before, and this now.
        val total = regional.sumOf { ConsensusBlender.weightOf(it, regional) }
        assertEquals(0.37, icon.sumOf { ConsensusBlender.weightOf(it, regional) } / total, 0.01)
    }

    /** The company is whoever publishes the quantity, not whoever is present at all. */
    @Test
    fun `a family two of whom publish a quantity is a family of two for it`() {
        val publishing = setOf(Source.ICON_CH1, Source.ICON_D2, Source.GEOSPHERE_AROME)
        assertEquals(1.0 / sqrt(2.0), ConsensusBlender.weightOf(Source.ICON_CH1, publishing), 1e-9)
        assertEquals(1.0, ConsensusBlender.weightOf(Source.GEOSPHERE_AROME, publishing), 1e-9)
    }

    /**
     * The equivalence that makes the change reviewable: with one model per family the weighted
     * statistics are the unweighted ones, to the last bit — including the even-count median's
     * average of its middle pair, which a naive weighted median gets wrong by picking the lower.
     */
    @Test
    fun `with no kin the weighted median is exactly the plain one`() {
        val odd = mapOf(
            Source.SIAG_KMOS to 1.0, Source.GEOSPHERE_AROME to 5.0, Source.ECMWF to 9.0,
        )
        assertEquals(ConsensusBlender.median(odd.values.toList()), ConsensusBlender.weightedMedian(odd), 1e-9)

        val even = odd + (Source.ECMWF_AIFS to 11.0)
        assertEquals(7.0, ConsensusBlender.median(even.values.toList()), 1e-9)
        assertEquals(
            "an even count averages its middle pair, weighted or not",
            ConsensusBlender.median(even.values.toList()),
            ConsensusBlender.weightedMedian(even),
            1e-9,
        )
    }

    @Test
    fun `the weighted median moves toward the family that is not repeating itself`() {
        // Four ICONs saying 10 and one AROME saying 20. Counted one for one the median is 10 flat;
        // weighted, ICON is worth 2,0 against AROME's 1,0 and the middle of the weight is still in
        // the ICON block — but add the two HARMONIE runs agreeing with AROME and it moves.
        val leaning = mapOf(
            Source.ICON_CH1 to 10.0, Source.ICON_CH2 to 10.0, Source.ICON_2I to 10.0, Source.ICON_D2 to 10.0,
            Source.GEOSPHERE_AROME to 20.0, Source.KNMI_HARMONIE to 20.0, Source.DMI_HARMONIE to 20.0,
        )
        assertEquals("counted one for one, ICON's four carry it", 10.0, ConsensusBlender.median(leaning.values.toList()), 1e-9)
        // ICON 4 x 0,5 = 2,0 against AROME 1,0 + HARMONIE 2 x 0,7071 = 2,414: the other side is now
        // the heavier one, and the median is theirs.
        assertEquals(20.0, ConsensusBlender.weightedMedian(leaning), 1e-9)
    }

    @Test
    fun `every source belongs to exactly one family and every family has a member`() {
        assertEquals(
            "a family nothing belongs to is a leftover",
            ModelFamily.entries.toSet(),
            Source.entries.map { it.family }.toSet(),
        )
    }
}
