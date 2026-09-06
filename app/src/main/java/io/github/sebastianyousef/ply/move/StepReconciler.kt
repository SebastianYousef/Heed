package io.github.sebastianyousef.ply.move

/**
 * One observation of the hardware step counter.
 *
 * @param counter `Sensor.TYPE_STEP_COUNTER`'s value: steps since the device booted, not
 *        since anything this app did. It is the whole reason this file exists.
 * @param elapsedNanos `SystemClock.elapsedRealtimeNanos()` at the moment observed —
 *        `SensorEvent.timestamp` for a batched event. Monotonic *within a boot* and reset
 *        to zero by one, which is what makes it an exact reboot detector.
 * @param atMillis wall-clock time of the observation, for deciding which day and hour the
 *        steps belong to.
 */
data class StepReading(
    val counter: Long,
    val elapsedNanos: Long,
    val atMillis: Long,
)

/**
 * Everything that must be remembered between one reading and the next.
 *
 * Persisted, because the process does not survive and the sensor does not remember what
 * has already been counted.
 */
data class StepCursor(
    val counter: Long,
    val elapsedNanos: Long,
)

/** Steps that happened, and roughly when. */
data class StepDelta(val steps: Int, val atMillis: Long)

/** What a batch of readings produced, and where to resume from. */
data class StepAdvance(
    val deltas: List<StepDelta>,
    val cursor: StepCursor,
    /** Readings whose value could not be believed, kept so the status screen can say so. */
    val discarded: Int = 0,
)

/**
 * Turns a counter that only ever goes up — until it doesn't — into steps that happened.
 *
 * `TYPE_STEP_COUNTER` reports steps since boot. That is the right design for the sensor
 * and the wrong shape for a step count, and every bug a pedometer has lives in the gap:
 *
 * - **It resets to zero on reboot.** A naive `now - lastSeen` goes sharply negative and
 *   either subtracts a day's steps or, if clamped, throws away everything since the boot.
 * - **It can reset without a reboot**, when the sensor hub restarts on its own.
 * - **It counted before this app existed.** A phone up for four days hands over 30,000
 *   steps on first read, and crediting them to today would be a visible lie.
 * - **Readings arrive late and out of order.** Batching is the whole reason the sensor is
 *   cheap — the hub buffers for an hour rather than waking the CPU per step — so a batch
 *   contains events timestamped across that hour, and each belongs to its own hour.
 *
 * Reboot is detected on [StepReading.elapsedNanos] rather than on the counter falling or
 * on a wall-clock boot estimate. Elapsed time is monotonic within a boot and starts again
 * at zero after one, so it is exact: no tolerance to tune, and immune to the clock being
 * corrected by NTP, which a boot time computed as `now - elapsedRealtime` is not. The
 * counter falling is still treated as a reset, because that is the sensor-hub case and it
 * happens without the elapsed clock moving.
 *
 * A pure function over explicit state, so the reboot cases can be tested without rebooting
 * anything.
 */
object StepReconciler {

    /**
     * More steps than any gap between readings can honestly contain.
     *
     * A sensor that comes back with a wild value should not be able to write a million
     * steps into a day that cannot then be told apart from a real one. Set well above any
     * plausible reading — weeks of walking — so it only ever catches genuine garbage, and
     * counted rather than silently dropped so the discard is visible.
     */
    const val SANITY_CAP = 200_000

    /**
     * @param cursor what was left over from last time, or null on the very first reading.
     * @param readings in the order the sensor produced them.
     */
    fun advance(cursor: StepCursor?, readings: List<StepReading>): StepAdvance? {
        if (readings.isEmpty()) return null

        var current = cursor
        var discarded = 0
        val deltas = mutableListOf<StepDelta>()

        for (reading in readings) {
            if (reading.counter < 0) {
                discarded++
                continue
            }
            val previous = current
            val steps: Int = when {
                // Nothing to compare against. The counter's existing value belongs to
                // whatever was happening before this app was watching, so it becomes the
                // baseline and contributes nothing. Starting at zero on install is honest;
                // crediting four days of walking to the afternoon is not.
                previous == null -> 0

                // The elapsed clock went backwards, so the device booted between the two.
                // Everything the counter holds was walked after that boot, and therefore
                // after the last reading, so all of it is new.
                reading.elapsedNanos < previous.elapsedNanos -> reading.counter.toInt()

                // Same boot, counter lower than before: the sensor hub restarted and began
                // again from zero. Same conclusion, different cause.
                reading.counter < previous.counter -> reading.counter.toInt()

                else -> (reading.counter - previous.counter).toInt()
            }

            if (steps > SANITY_CAP) {
                // Believe the counter's position — it is probably right about where it is —
                // while refusing the implausible jump. Advancing the cursor is what stops
                // the same bad delta being offered again on the next reading.
                discarded++
                current = StepCursor(reading.counter, reading.elapsedNanos)
                continue
            }

            if (steps > 0) deltas += StepDelta(steps, reading.atMillis)
            current = StepCursor(reading.counter, reading.elapsedNanos)
        }

        return StepAdvance(deltas, current ?: return null, discarded)
    }
}
