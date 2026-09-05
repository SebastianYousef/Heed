package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.AppCategory
import io.github.sebastianyousef.heed.ui.UsageSlice
import io.github.sebastianyousef.heed.ui.orderSlices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the bar is built in, which is the difference between a chart you can read
 * across a week and one whose colours move about.
 */
class UsageSliceTest {

    private fun slice(label: String, ms: Long, argb: Int? = null, category: AppCategory = AppCategory.NEUTRAL) =
        UsageSlice(label = label, argb = argb, category = category, ms = ms)

    @Test
    fun `the uncoloured remainder is always the base of the bar`() {
        val ordered = orderSlices(
            listOf(
                slice("Everything else", 100),
                slice("Feeds", 10, argb = 0xFFE5484D.toInt()),
                slice("Distracting", 20, category = AppCategory.DISTRACTING),
            )
        )
        assertEquals("Everything else", ordered.last().label)
        assertEquals("Feeds", ordered.first().label)
    }

    @Test
    fun `a coloured group outranks a category, and bigger outranks smaller`() {
        val ordered = orderSlices(
            listOf(
                slice("Distracting", 500, category = AppCategory.DISTRACTING),
                slice("Feeds", 10, argb = 1),
                slice("Video", 30, argb = 2),
            )
        )
        assertEquals(listOf("Video", "Feeds", "Distracting"), ordered.map { it.label })
    }

    @Test
    fun `an empty slice is dropped rather than drawn as a hairline`() {
        val ordered = orderSlices(listOf(slice("Feeds", 0, argb = 1), slice("Everything else", 5)))
        assertEquals(1, ordered.size)
        assertTrue(ordered.single().plain)
    }
}
