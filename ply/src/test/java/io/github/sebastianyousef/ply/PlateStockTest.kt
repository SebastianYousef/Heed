package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.data.Settings
import io.github.sebastianyousef.ply.train.Plates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The plate inventory is a setting stored as a string, so its parser is the one place a
 * typo turns into a silently wrong plate calculation.
 */
class PlateStockTest {

    @Test
    fun `a stock round-trips`() {
        val encoded = Settings.encodeStock(Plates.DEFAULT_STOCK)
        assertEquals(Plates.DEFAULT_STOCK, Settings.decodeStock(encoded))
    }

    @Test
    fun `it is written heaviest first regardless of the order given`() {
        val encoded = Settings.encodeStock(
            listOf(Plates.Stock(5_000, 2), Plates.Stock(20_000, 4))
        )
        assertEquals("20000:4,5000:2", encoded)
    }

    @Test
    fun `a plate you own none of is not written down`() {
        val encoded = Settings.encodeStock(
            listOf(Plates.Stock(20_000, 4), Plates.Stock(15_000, 0))
        )
        assertEquals("20000:4", encoded)
    }

    @Test
    fun `nothing stored falls back to the default rather than to an empty gym`() {
        assertNull(Settings.decodeStock(null))
        assertNull(Settings.decodeStock(""))
    }

    @Test
    fun `a partly unreadable value is refused rather than repaired`() {
        // Half an inventory would calculate plates that are wrong without looking wrong,
        // which is worse than falling back to the default and being obviously not yours.
        assertNull(Settings.decodeStock("20000:4,rubbish,5000:2"))
        assertNull(Settings.decodeStock("20000"))
        assertNull(Settings.decodeStock("20000:0,5000:x"))
    }
}
