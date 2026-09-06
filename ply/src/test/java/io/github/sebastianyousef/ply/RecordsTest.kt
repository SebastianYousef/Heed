package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.train.PreviousBests
import io.github.sebastianyousef.ply.train.RecordKind
import io.github.sebastianyousef.ply.train.Records
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {

    private val history = PreviousBests(
        heaviestGrams = 100_000,
        estimatedGrams = 116_667,          // 100 x 5
        heaviestAtReps = mapOf(1 to 100_000, 5 to 100_000, 8 to 85_000),
    )

    @Test
    fun `adding a rep at the same weight sets the rep record and the estimate`() {
        val broken = Records.brokenBy(100_000, 6, warmUp = false, previous = history)
        assertEquals(setOf(RecordKind.ESTIMATED, RecordKind.AT_REPS), broken)
    }

    @Test
    fun `a heavy single is the heaviest without being the best estimate`() {
        // 105 x 1 estimates to 105, which is below the 116.7 from 100 x 5. Both statements
        // are true at once, which is exactly why they are separate records.
        val broken = Records.brokenBy(105_000, 1, warmUp = false, previous = history)
        assertTrue(RecordKind.HEAVIEST in broken)
        assertTrue(RecordKind.AT_REPS in broken)
        assertTrue(RecordKind.ESTIMATED !in broken)
    }

    @Test
    fun `repeating a best is not a new record`() {
        assertEquals(emptySet<RecordKind>(), Records.brokenBy(85_000, 8, false, history))
    }

    @Test
    fun `a rep count never attempted before is a record at that count`() {
        val broken = Records.brokenBy(60_000, 12, warmUp = false, previous = history)
        assertEquals(setOf(RecordKind.AT_REPS), broken)
    }

    @Test
    fun `a set above twelve reps can still be the heaviest but never the best estimate`() {
        val broken = Records.brokenBy(110_000, 15, warmUp = false, previous = history)
        assertTrue(RecordKind.HEAVIEST in broken)
        assertTrue(RecordKind.ESTIMATED !in broken)
    }

    @Test
    fun `warming up sets no records`() {
        assertEquals(emptySet<RecordKind>(), Records.brokenBy(200_000, 1, warmUp = true, previous = history))
    }

    @Test
    fun `the first set of a new exercise announces nothing`() {
        val fresh = PreviousBests()
        val broken = Records.brokenBy(60_000, 8, warmUp = false, previous = fresh)
        // Technically three records at once, which reads as a bug rather than as a result.
        assertEquals(3, broken.size)
        assertEquals(emptySet<RecordKind>(), Records.worthAnnouncing(broken, fresh))
    }
}
