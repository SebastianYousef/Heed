package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.move.StepCursor
import io.github.sebastianyousef.ply.move.StepReading
import io.github.sebastianyousef.ply.move.StepReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reboot cases, tested without rebooting anything — which is the whole reason the
 * reconciler takes its state as an argument instead of reading it.
 */
class StepReconcilerTest {

    private val second = 1_000_000_000L

    private fun reading(counter: Long, elapsedSeconds: Long, atMillis: Long = 0) =
        StepReading(counter, elapsedSeconds * second, atMillis)

    @Test
    fun `the first reading is a baseline and credits nothing`() {
        // The phone had been up four days when the app was installed. Crediting those
        // 30,000 steps to this afternoon would be a visible lie.
        val advance = StepReconciler.advance(null, listOf(reading(30_000, 345_600)))!!
        assertEquals(emptyList<Any>(), advance.deltas)
        assertEquals(30_000L, advance.cursor.counter)
    }

    @Test
    fun `an ordinary reading credits the difference`() {
        val cursor = StepCursor(counter = 1_000, elapsedNanos = 100 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(1_450, 200)))!!
        assertEquals(450, advance.deltas.single().steps)
        assertEquals(1_450L, advance.cursor.counter)
    }

    @Test
    fun `a reboot credits everything the counter holds, not a negative difference`() {
        // Elapsed time going backwards is the exact signal: monotonic within a boot, zero
        // after one. A wall-clock boot estimate would need a tolerance and would be thrown
        // by the clock being corrected.
        val cursor = StepCursor(counter = 9_000, elapsedNanos = 80_000 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(120, 30)))!!
        assertEquals(120, advance.deltas.single().steps)
    }

    @Test
    fun `a reboot after which more was walked than before is still credited in full`() {
        // The counter is higher than last time, so nothing looks wrong — and without the
        // elapsed-clock check this would credit 1,000 instead of 6,000.
        val cursor = StepCursor(counter = 5_000, elapsedNanos = 90_000 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(6_000, 200)))!!
        assertEquals(6_000, advance.deltas.single().steps)
    }

    @Test
    fun `the sensor hub restarting without a reboot is treated as a reset`() {
        // Elapsed time keeps climbing, so this is not a reboot; the counter has simply
        // begun again from zero.
        val cursor = StepCursor(counter = 5_000, elapsedNanos = 100 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(40, 200)))!!
        assertEquals(40, advance.deltas.single().steps)
    }

    @Test
    fun `a batch is attributed event by event, not lumped onto the moment it arrived`() {
        // The sensor hub buffers for an hour so the CPU is not woken per step, so a batch
        // spans that hour and each part of it belongs to its own hour.
        val cursor = StepCursor(counter = 0, elapsedNanos = 0)
        val advance = StepReconciler.advance(
            cursor,
            listOf(
                reading(100, 600, atMillis = 1_000),
                reading(250, 1_200, atMillis = 2_000),
                reading(260, 1_800, atMillis = 3_000),
            ),
        )!!
        assertEquals(listOf(100, 150, 10), advance.deltas.map { it.steps })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), advance.deltas.map { it.atMillis })
    }

    @Test
    fun `a reading that did not move produces no delta at all`() {
        val cursor = StepCursor(counter = 700, elapsedNanos = 100 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(700, 200)))!!
        assertEquals(emptyList<Any>(), advance.deltas)
        assertEquals(700L, advance.cursor.counter)
    }

    @Test
    fun `an implausible jump is refused but still moves the cursor past it`() {
        // Advancing the cursor is what stops the same bad delta being offered again on
        // every reading from here on.
        val cursor = StepCursor(counter = 1_000, elapsedNanos = 100 * second)
        val advance = StepReconciler.advance(cursor, listOf(reading(9_000_000, 200)))!!
        assertEquals(emptyList<Any>(), advance.deltas)
        assertEquals(1, advance.discarded)
        assertEquals(9_000_000L, advance.cursor.counter)
    }

    @Test
    fun `nothing to read means nothing to write`() {
        assertNull(StepReconciler.advance(StepCursor(1, 1), emptyList()))
    }

    @Test
    fun `a reboot in the middle of a batch is handled at the event it happened`() {
        val cursor = StepCursor(counter = 4_000, elapsedNanos = 50_000 * second)
        val advance = StepReconciler.advance(
            cursor,
            listOf(
                reading(4_200, 50_600),   // before the reboot: 200
                reading(30, 40),          // elapsed went backwards: all 30 are new
                reading(90, 300),         // and 60 more after that
            ),
        )!!
        assertEquals(listOf(200, 30, 60), advance.deltas.map { it.steps })
    }
}
