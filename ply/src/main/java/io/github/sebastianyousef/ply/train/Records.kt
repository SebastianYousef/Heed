package io.github.sebastianyousef.ply.train

/**
 * The kinds of best there are, because they disagree and the app has to say which it means.
 *
 * This is the question every training app answers badly by not asking it. "Personal record"
 * sounds like one thing and is at least three, which conflict constantly: adding a rep at
 * the same weight beats your previous set on one measure and not on another, and a heavy
 * single beats a hard set of eight on one measure while being worse training on every
 * other. An app that lights up a trophy without saying which of these it meant teaches you
 * to ignore the trophy.
 *
 * So each is named, each is shown as what it is, and none of them is called *the* record.
 */
enum class RecordKind {
    /**
     * The heaviest this exercise has ever been loaded, at any rep count.
     *
     * The one people mean by "my max". Blind to volume: one grinding single beats every
     * set of ten you have ever done, which is correct for what it measures and useless as
     * a measure of training.
     */
    HEAVIEST,

    /**
     * The best estimated one-rep max, from [OneRepMax].
     *
     * The one worth watching, because it is the only one that can be beaten by a set that
     * was neither the heaviest nor the longest — which is what progress usually looks like.
     * Absent for sets above [OneRepMax.MAX_REPS], where there is no honest estimate.
     */
    ESTIMATED,

    /**
     * The heaviest ever lifted *for this exact rep count*.
     *
     * The one that makes a session feel like it went somewhere. Beating your best set of
     * eight is a real result even in a week where neither of the others moved, and it is
     * the record that a person following a program actually beats week to week.
     */
    AT_REPS,
}

/** What was already true for an exercise before the set being judged. */
data class PreviousBests(
    val heaviestGrams: Int = 0,
    val estimatedGrams: Int = 0,
    /** Heaviest ever lifted at each rep count. Absent means never attempted at that count. */
    val heaviestAtReps: Map<Int, Int> = emptyMap(),
)

/**
 * Which records a set breaks, judged against what was true before it.
 *
 * A pure function taking the previous state explicitly, rather than a method that reads
 * the database, and the reason is a lesson from the previous app: two copies of the
 * scrolling decision existed, tests exercised one of them, and the phone ran the other —
 * so the tests passed for weeks against code that never executed. There is one copy of
 * this rule, the tests call it, and the repository calls it.
 *
 * Warm-up sets never set records. They are not attempts, and a warm-up single at a weight
 * you have not touched in months would otherwise report a personal best for standing under
 * the bar.
 */
object Records {

    fun brokenBy(
        weightGrams: Int,
        reps: Int,
        warmUp: Boolean,
        previous: PreviousBests,
    ): Set<RecordKind> {
        if (warmUp || weightGrams <= 0 || reps < 1) return emptySet()

        val broken = mutableSetOf<RecordKind>()
        if (weightGrams > previous.heaviestGrams) broken += RecordKind.HEAVIEST

        OneRepMax.estimate(weightGrams, reps)?.let { estimate ->
            if (estimate > previous.estimatedGrams) broken += RecordKind.ESTIMATED
        }

        // Strictly greater, so repeating a best is not a new record. Equalling something is
        // worth seeing in the history and is not worth being told about — a notification
        // that fires every time you do what you did last week stops being a signal.
        val bestAtThisCount = previous.heaviestAtReps[reps] ?: 0
        if (weightGrams > bestAtThisCount) broken += RecordKind.AT_REPS

        return broken
    }

    /**
     * The first set at a new rep count is a record at that count, but only when the
     * exercise has been done before. Otherwise the very first set of an exercise reports
     * three records at once, which is technically true and reads as a bug.
     */
    fun worthAnnouncing(broken: Set<RecordKind>, previous: PreviousBests): Set<RecordKind> =
        if (previous.heaviestGrams == 0) emptySet() else broken
}
