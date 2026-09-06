package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.train.Load
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadTest {

    @Test
    fun `stepping up from an off-grid weight snaps onto the grid`() {
        // 81 kg with a 2.5 kg increment gives 82.5, not 83.5. A weight arrived at some
        // other way should be pulled onto the grid by the first press rather than carrying
        // its offset forever.
        assertEquals(82_500, Load.step(81_000, 2_500, up = true))
        assertEquals(80_000, Load.step(81_000, 2_500, up = false))
    }

    @Test
    fun `stepping from on the grid moves exactly one increment`() {
        assertEquals(82_500, Load.step(80_000, 2_500, up = true))
        assertEquals(77_500, Load.step(80_000, 2_500, up = false))
    }

    @Test
    fun `weight never goes below nothing`() {
        assertEquals(0, Load.step(1_000, 2_500, up = false))
        assertEquals(0, Load.step(0, 2_500, up = false))
    }

    @Test
    fun `trailing zeroes are dropped and real decimals are kept`() {
        assertEquals("80", Load.format(80_000, Load.Unit.KG))
        assertEquals("82.5", Load.format(82_500, Load.Unit.KG))
        assertEquals("1.25", Load.format(1_250, Load.Unit.KG))
    }

    @Test
    fun `a weight loaded in one unit reads honestly in the other`() {
        // 100 kg really is 220.46 lb. Rounding that to 220.5 would be tidier and would be
        // claiming the bar held something it did not.
        assertEquals("220.46", Load.format(100_000, Load.Unit.LB))
        assertEquals("45", Load.format(Load.kgToGrams(20.4117), Load.Unit.LB))
    }

    @Test
    fun `grams are exact where a float would drift`() {
        // The reason storage is an integer: 62.5 is not representable in binary floating
        // point, so forty sets of it accumulate an error that eventually decides a record.
        val total = (1..40).sumOf { Load.kgToGrams(62.5).toLong() }
        assertEquals(2_500_000L, total)
    }
}
