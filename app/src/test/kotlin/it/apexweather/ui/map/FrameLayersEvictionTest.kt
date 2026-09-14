package it.apexweather.ui.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [providersToEvict] is the pure rule behind [FrameLayers]' pruning: a provider is only ever
 * dropped when the current frame list no longer wants it *and* nothing on screen is painting from
 * it right now. Tested without a MapView because the rule itself never touches one.
 */
class FrameLayersEvictionTest {

    private val t1 = Instant.parse("2026-09-14T10:00:00Z")
    private val t2 = Instant.parse("2026-09-14T10:10:00Z")
    private val t3 = Instant.parse("2026-09-14T10:20:00Z")

    @Test
    fun `a shown frame absent from the new list is kept`() {
        val evicted = providersToEvict(
            held = setOf(t1, t2),
            wanted = setOf(t2),
            protected = setOf(t1),
        )
        assertEquals(emptySet<Instant>(), evicted)
    }

    @Test
    fun `an outgoing frame is kept`() {
        // t1 is not wanted by the new list either, but it is still fading out on screen.
        val evicted = providersToEvict(
            held = setOf(t1, t2),
            wanted = setOf(t2),
            protected = setOf(t1),
        )
        assertEquals(emptySet<Instant>(), evicted)
    }

    @Test
    fun `a frame neither wanted nor protected is evicted`() {
        val evicted = providersToEvict(
            held = setOf(t1, t2, t3),
            wanted = setOf(t2),
            protected = setOf(t3),
        )
        assertEquals(setOf(t1), evicted)
    }

    @Test
    fun `the empty case evicts nothing`() {
        val evicted = providersToEvict(held = emptySet(), wanted = emptySet(), protected = emptySet())
        assertEquals(emptySet<Instant>(), evicted)
    }
}
