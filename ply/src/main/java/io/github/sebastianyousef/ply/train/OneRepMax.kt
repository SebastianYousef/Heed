package io.github.sebastianyousef.ply.train

import kotlin.math.roundToInt

/**
 * What a set says about the most you could lift once.
 *
 * The point of an estimate is to make sets at different weights and rep counts
 * comparable, so that 100 kg × 5 and 90 kg × 8 can be ranked and a trend can be drawn
 * through months of sets that were never one-rep attempts. Without it, "am I getting
 * stronger" is only answerable by comparing sets that happen to share a rep count.
 *
 * **Epley**, `w × (1 + r/30)`, chosen over Brzycki and the rest for one reason: the
 * formulae agree closely up to about five reps and diverge sharply above it, so the choice
 * only matters in the range where none of them is trustworthy anyway. Epley is the one
 * whose failure mode is legible — it is linear in reps, so it is obvious by inspection
 * that a twenty-rep set is being multiplied by 1.67 and that this is nonsense.
 *
 * Which is why it refuses. Above [MAX_REPS] this returns null and the app prints nothing
 * rather than a number it cannot stand behind. An estimate that is quietly wrong is worse
 * than an absent one, because a trend line drawn through it looks exactly as authoritative
 * as a trend line through good data.
 */
object OneRepMax {

    /**
     * Twelve. Above this the estimate is dominated by how long someone can tolerate
     * discomfort rather than by how strong they are, and the error passes the size of the
     * differences anyone is trying to detect.
     */
    const val MAX_REPS = 12

    /**
     * The estimate in grams, or null when there is nothing honest to say.
     *
     * A single rep is returned unchanged rather than run through the formula: a 1RM is
     * not an estimate of itself, and `w × (1 + 1/30)` would report a set of one as 3%
     * heavier than it was — which would make every genuine max attempt set a fake record.
     */
    fun estimate(weightGrams: Int, reps: Int): Int? = when {
        weightGrams <= 0 -> null
        reps < 1 || reps > MAX_REPS -> null
        reps == 1 -> weightGrams
        else -> (weightGrams * (1.0 + reps / 30.0)).roundToInt()
    }

    /**
     * The weight that would need [reps] reps to match a given estimate — the inverse.
     *
     * Used to answer "what should I put on the bar for a set of eight" from a known
     * estimate, and to place a target on the logging screen. Same refusal above
     * [MAX_REPS], for the same reason.
     */
    fun weightFor(oneRepMaxGrams: Int, reps: Int): Int? = when {
        oneRepMaxGrams <= 0 -> null
        reps < 1 || reps > MAX_REPS -> null
        reps == 1 -> oneRepMaxGrams
        else -> (oneRepMaxGrams / (1.0 + reps / 30.0)).roundToInt()
    }
}
