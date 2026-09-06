package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.train.Plates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatesTest {

    @Test
    fun `a hundred kilos is a twenty-five and a fifteen a side`() {
        // Forty a side, heaviest first: 25 then 15. Not 20 + 20, which is the same plate
        // count and is what a person often reaches for — greedy takes the heavier first,
        // and with the standard metric set it can never strand a remainder by doing so.
        val plan = Plates.plan(100_000)!!
        assertEquals(listOf(25_000, 15_000), plan.perSide)
        assertEquals(100_000, plan.achievedGrams)
        assertTrue(plan.exact)
    }

    @Test
    fun `the bar alone needs no plates and is not an error`() {
        val plan = Plates.plan(20_000)!!
        assertEquals(emptyList<Int>(), plan.perSide)
        assertTrue(plan.exact)
    }

    @Test
    fun `below the bar there is nothing to say`() {
        // A dumbbell press or a machine. An empty plan would read as "load nothing".
        assertNull(Plates.plan(15_000))
    }

    @Test
    fun `an awkward inventory falls short and says by how much`() {
        val stock = listOf(Plates.Stock(20_000, 4), Plates.Stock(15_000, 2))
        val plan = Plates.plan(85_000, stock = stock)!!
        // 32.5 a side wanted; 30 reachable. Silently rounding is how a log stops matching
        // what was on the bar.
        assertEquals(listOf(20_000), plan.perSide)
        assertEquals(60_000, plan.achievedGrams)
        assertEquals(25_000, plan.shortfallGrams)
        assertTrue(!plan.exact)
    }

    @Test
    fun `running out of a size falls through to the next one down`() {
        val stock = listOf(Plates.Stock(20_000, 1), Plates.Stock(10_000, 4))
        val plan = Plates.plan(100_000, stock = stock)!!
        assertEquals(listOf(20_000, 10_000, 10_000), plan.perSide)
        assertTrue(plan.exact)
    }

    @Test
    fun `an odd gram cannot be split across two sides and is reported as short`() {
        val plan = Plates.plan(20_001)!!
        assertEquals(20_000, plan.achievedGrams)
        assertEquals(1, plan.shortfallGrams)
    }

    @Test
    fun `a different bar shifts everything by its own weight`() {
        val plan = Plates.plan(75_000, barGrams = 15_000)!!
        assertEquals(listOf(25_000, 5_000), plan.perSide)
        assertTrue(plan.exact)
    }
}
