package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.train.OneRepMax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneRepMaxTest {

    @Test
    fun `a single is its own maximum, not three percent more than itself`() {
        // The formula would return 103.3 kg here. A max attempt that reported a record
        // heavier than the bar would make every genuine single set a fake one.
        assertEquals(100_000, OneRepMax.estimate(100_000, 1))
    }

    @Test
    fun `Epley on a set of five`() {
        // 100 x (1 + 5/30) = 116.67
        assertEquals(116_667, OneRepMax.estimate(100_000, 5))
    }

    @Test
    fun `it refuses above twelve rather than printing a number it cannot stand behind`() {
        assertEquals(133_333, OneRepMax.estimate(100_000, 10))
        assertNull(OneRepMax.estimate(100_000, 13))
        assertNull(OneRepMax.estimate(100_000, 20))
    }

    @Test
    fun `nonsense in, nothing out`() {
        assertNull(OneRepMax.estimate(0, 5))
        assertNull(OneRepMax.estimate(-1, 5))
        assertNull(OneRepMax.estimate(100_000, 0))
    }

    @Test
    fun `the inverse round-trips within a gram or two`() {
        val estimate = OneRepMax.estimate(100_000, 8)!!
        val back = OneRepMax.weightFor(estimate, 8)!!
        assertEquals(100_000.0, back.toDouble(), 2.0)
    }

    @Test
    fun `a heavier set of five beats a lighter set of eight, and the estimate says so`() {
        val fives = OneRepMax.estimate(100_000, 5)!!
        val eights = OneRepMax.estimate(90_000, 8)!!
        // 116.7 vs 114.0 — close, which is the point: without the estimate these two sets
        // cannot be ranked at all.
        assert(fives > eights)
    }
}
