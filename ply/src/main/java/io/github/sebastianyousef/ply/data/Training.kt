package io.github.sebastianyousef.ply.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.sebastianyousef.ply.train.OneRepMax

/**
 * A workout: when it started, when it ended, and what it was called.
 *
 * The sets are not in here. They are their own rows pointing back, so that a session can
 * be open while sets are appended to it one at a time without rewriting anything — which
 * is the actual shape of using the app, and the reason a session is not a document.
 *
 * [endedAt] being null is what "in progress" means. There is exactly one such row at a
 * time, enforced in the repository rather than by the schema: SQLite can express a partial
 * unique index for it, but the failure mode of the constraint — an insert that throws on
 * the one screen that must never fail — is worse than the failure mode of the check.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("startedAt"), Index("endedAt")],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val startedAt: Long,

    /** Null while it is running. */
    val endedAt: Long? = null,

    /** The routine this was started from, if any. Kept for "how did last Tuesday go". */
    val routineId: Long? = null,

    /** From the routine, or typed, or the time of day. Never empty on screen. */
    val title: String = "",

    val note: String? = null,
) {
    val inProgress: Boolean get() = endedAt == null
}

/** Whether a set was the work or the run-up to it. */
enum class SetKind {
    /** Counts towards volume, towards records, towards everything. */
    WORKING,

    /**
     * Does not count towards anything.
     *
     * Logged anyway, because the weights you warmed up with are how you decide what to
     * warm up with next time, and because a session that shows only working sets is a
     * poor account of forty minutes. Excluded from volume so that a long warm-up does not
     * read as a hard session, and from records so that a light single at a weight you have
     * not touched in months does not report a personal best for standing under the bar.
     */
    WARMUP,
}

/**
 * One set. The atom the whole app is built to record quickly and never lose.
 *
 * ### Why there are three weight columns
 *
 * [weightGrams] is what was *added* — the bar and its plates, the dumbbell, the belt on a
 * dip. For a pull-up it is zero, and for an assisted one it is negative.
 *
 * [bodyweightGrams] is what the lifter weighed, snapshotted, and only present for
 * exercises where that is part of the load. Snapshotted rather than looked up, because
 * bodyweight changes and a pull-up done at 78 kg does not retroactively become a heavier
 * set when you gain three.
 *
 * [effectiveGrams] is the sum, and is what every comparison and aggregate uses. It is
 * stored rather than computed in each query because it is a pure function of two columns
 * in its own row — it cannot go stale — and because the alternative is writing
 * `weightGrams + COALESCE(bodyweightGrams, 0)` into every statistic and getting it right
 * every time.
 *
 * ### Why the estimate is stored
 *
 * Same reason, and a sharper one. "Best estimated 1RM before this moment" wants to be a
 * `MAX()` over an index. Computing it in SQL would mean writing Epley *and* its refusal
 * above twelve reps into SQL as well as into Kotlin — two implementations of one rule,
 * which is exactly the arrangement that let the previous app's tests pass for weeks
 * against a code path the phone never ran. The rule lives in [OneRepMax], the column holds
 * what it returned, and null means it declined to guess.
 */
@Entity(
    tableName = "sets",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        // The index every record query rides: the best at an exercise, before a moment.
        Index("exerciseId", "completedAt"),
    ],
)
data class WorkSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val sessionId: Long,
    val exerciseId: String,

    /** Order within the session, so the log reads back the way it was performed. */
    val position: Int,

    /** What was added: plates, dumbbell, belt. Negative for assistance. */
    val weightGrams: Int,

    val reps: Int,

    val kind: SetKind = SetKind.WORKING,

    /** Bodyweight at the time, for exercises where it is part of the load. */
    val bodyweightGrams: Int? = null,

    /** [weightGrams] plus [bodyweightGrams]. What everything is measured against. */
    val effectiveGrams: Int,

    /** From [OneRepMax], or null where there was nothing honest to say. */
    val e1rmGrams: Int? = null,

    /**
     * How hard it was, 6..10, or null.
     *
     * Optional and entered after the fact, never before: a field that stands between you
     * and logging the set is a field that makes you stop logging sets.
     */
    val rpe: Float? = null,

    /** For a plank or a hang, where the unit of work is time rather than repetitions. */
    val holdSeconds: Int? = null,

    val completedAt: Long,

    val note: String? = null,
) {
    companion object {
        /**
         * Builds a set with its two derived columns filled in.
         *
         * A factory rather than default arguments, so there is no way to write a row whose
         * effective load and estimate disagree with its weight and reps.
         */
        fun of(
            sessionId: Long,
            exerciseId: String,
            position: Int,
            weightGrams: Int,
            reps: Int,
            kind: SetKind = SetKind.WORKING,
            bodyweightGrams: Int? = null,
            rpe: Float? = null,
            holdSeconds: Int? = null,
            completedAt: Long = System.currentTimeMillis(),
            note: String? = null,
        ): WorkSet {
            val effective = weightGrams + (bodyweightGrams ?: 0)
            return WorkSet(
                sessionId = sessionId,
                exerciseId = exerciseId,
                position = position,
                weightGrams = weightGrams,
                reps = reps,
                kind = kind,
                bodyweightGrams = bodyweightGrams,
                effectiveGrams = effective,
                e1rmGrams = if (kind == SetKind.WORKING) {
                    OneRepMax.estimate(effective, reps)
                } else {
                    // A warm-up is not an attempt, so it gets no estimate at all rather
                    // than one that is merely filtered out of the record queries later.
                    null
                },
                rpe = rpe,
                holdSeconds = holdSeconds,
                completedAt = completedAt,
                note = note,
            )
        }
    }
}
