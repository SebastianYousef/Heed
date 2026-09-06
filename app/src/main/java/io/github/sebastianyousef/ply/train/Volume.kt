package io.github.sebastianyousef.ply.train

/**
 * One set, reduced to what volume arithmetic needs from it.
 *
 * Deliberately not the database row. The aggregation is a rule, it is the kind of rule
 * that gets quietly changed, and it should be testable without a database — so it takes
 * the three facts it uses and nothing else.
 */
data class VolumeSet(
    val weightGrams: Int,
    val reps: Int,
    val warmUp: Boolean,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
)

/** What a week of training did to one muscle. */
data class MuscleVolume(
    val muscle: String,
    /**
     * Sets that count, with secondary involvement counted at [Volume.SECONDARY_SHARE].
     * Fractional for exactly that reason, and rounded only for display.
     */
    val hardSets: Double,
    /** Weight moved, in grams·reps. The other way of counting, kept beside it. */
    val tonnageGrams: Long,
)

/**
 * How much work a muscle got, counted two ways because there is no single right way.
 *
 * **Hard sets** is the count most training literature is written in, and the one that
 * tracks how much a week actually asked of you. It is blind to load: a set of five at 60%
 * counts the same as a set of five at 90%.
 *
 * **Tonnage** is weight times reps, which is sensitive to load and therefore rewards
 * exactly the thing hard sets ignore — and is dominated by leg work, because a squat moves
 * five times the mass of a curl regardless of how hard either was.
 *
 * Both are shown, and the app says which is which. An app that prints one number called
 * "volume" is picking one of these on your behalf and not telling you which.
 *
 * ### The two rules that make the numbers mean something
 *
 * **Warm-ups are excluded.** They are not stimulus, and counting them makes a session
 * whose warm-ups were long look like a session that did more work.
 *
 * **Secondary muscles count at half.** A bench press is not zero triceps work and it is
 * not a triceps set either, and both of the tidy answers — count it fully, ignore it —
 * are visibly wrong to anyone who trains. Half is a convention rather than a measurement,
 * which is why the app states it on screen instead of presenting the total as a fact.
 */
object Volume {

    /**
     * The share of a set credited to a muscle the dataset lists as secondary.
     *
     * A constant rather than a setting. It is not knowable well enough to be worth
     * tuning, and a number the user can change is a number that makes two people's totals
     * incomparable while looking identical.
     */
    const val SECONDARY_SHARE = 0.5

    fun aggregate(sets: List<VolumeSet>): List<MuscleVolume> {
        val hardSets = mutableMapOf<String, Double>()
        val tonnage = mutableMapOf<String, Long>()

        for (set in sets) {
            if (set.warmUp || set.reps < 1) continue
            val moved = set.weightGrams.toLong() * set.reps

            // Primaries first, so that a muscle listed in both lists — which the dataset
            // does contain — is credited once, at the higher share, rather than 1.5 times.
            val primary = set.primaryMuscles.toSet()
            for (muscle in primary) {
                hardSets[muscle] = (hardSets[muscle] ?: 0.0) + 1.0
                tonnage[muscle] = (tonnage[muscle] ?: 0L) + moved
            }
            for (muscle in set.secondaryMuscles.toSet() - primary) {
                hardSets[muscle] = (hardSets[muscle] ?: 0.0) + SECONDARY_SHARE
                tonnage[muscle] = (tonnage[muscle] ?: 0L) + (moved * SECONDARY_SHARE).toLong()
            }
        }

        return hardSets.map { (muscle, count) ->
            MuscleVolume(muscle, count, tonnage[muscle] ?: 0L)
        }.sortedWith(compareByDescending<MuscleVolume> { it.hardSets }.thenBy { it.muscle })
    }

    /** Sets that count towards volume, for a figure printed beside a session. */
    fun workingSets(sets: List<VolumeSet>): Int = sets.count { !it.warmUp && it.reps >= 1 }

    /** Total weight moved in a session, warm-ups excluded. */
    fun tonnageGrams(sets: List<VolumeSet>): Long =
        sets.filter { !it.warmUp }.sumOf { it.weightGrams.toLong() * it.reps }
}
