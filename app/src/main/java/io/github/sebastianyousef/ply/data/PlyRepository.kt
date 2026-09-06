package io.github.sebastianyousef.ply.data

import android.content.Context
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.ply.move.StepCursor
import io.github.sebastianyousef.ply.move.StepReading
import io.github.sebastianyousef.ply.move.StepReconciler
import io.github.sebastianyousef.ply.train.MuscleVolume
import io.github.sebastianyousef.ply.train.PreviousBests
import io.github.sebastianyousef.ply.train.RecordKind
import io.github.sebastianyousef.ply.train.Records
import io.github.sebastianyousef.ply.train.Volume
import io.github.sebastianyousef.ply.train.VolumeSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What logging a set produced, so the screen can say something about it. */
data class LoggedSet(
    val set: WorkSet,
    /** Records this set broke, already filtered by [Records.worthAnnouncing]. */
    val records: Set<RecordKind>,
)

/**
 * The one place the rest of the app talks to storage.
 *
 * A process-lifetime singleton owning its own scope, rather than something handed a scope
 * by whichever component happened to construct it first. That is not a style preference:
 * the previous app borrowed a scope from a service that gets stopped whenever no rule
 * needs it, and cancelling that scope killed every cache collector while a run-once guard
 * refused to let anything restart them — so a cache stayed empty for the life of the
 * process and enforcement silently stopped working while still displaying as configured.
 * A lifetime that cannot be borrowed is a mistake that cannot be made twice.
 */
class PlyRepository private constructor(
    private val context: Context,
    val dao: PlyDao,
    val settings: Settings,
) {

    // ---- Sessions --------------------------------------------------------------------

    val openSession: Flow<Session?> = dao.openSession()

    /**
     * Starts a session, or returns the one already running.
     *
     * Never two at once, and the check lives here rather than in a partial unique index:
     * a constraint would turn "start a workout" into an operation that can throw, on the
     * one screen in the app that must never fail while somebody is standing in a gym.
     */
    suspend fun startSession(routineId: Long? = null, title: String = ""): Session {
        dao.openSessionNow()?.let { return it }
        val now = System.currentTimeMillis()
        val name = title.ifBlank { defaultTitle(now) }
        val id = dao.insertSession(Session(startedAt = now, routineId = routineId, title = name))
        // So the routine list can lead with the one being worked through rather than with
        // whichever was created first.
        routineId?.let { dao.touchRoutine(it, now) }
        return Session(id = id, startedAt = now, routineId = routineId, title = name)
    }

    suspend fun endSession() {
        val open = dao.openSessionNow() ?: return
        dao.updateSession(open.copy(endedAt = System.currentTimeMillis()))
    }

    private fun defaultTitle(now: Long): String = when (Time.hourOf(now)) {
        in 0..10 -> "Morning session"
        in 11..15 -> "Afternoon session"
        else -> "Evening session"
    }

    // ---- Sets ------------------------------------------------------------------------

    fun sets(sessionId: Long): Flow<List<SetWithExercise>> = dao.setsForSession(sessionId)

    /**
     * Writes a set and says what it beat.
     *
     * The records are judged against what was true strictly *before* this set, which is
     * why the bests are read before the insert rather than after: read them after and the
     * set beats itself, and everything is a personal record forever.
     */
    suspend fun logSet(
        sessionId: Long,
        exerciseId: String,
        weightGrams: Int,
        reps: Int,
        kind: SetKind = SetKind.WORKING,
        rpe: Float? = null,
        holdSeconds: Int? = null,
    ): LoggedSet {
        val now = System.currentTimeMillis()
        val exercise = dao.exercise(exerciseId)
        val bodyweight = if (exercise?.bodyweightLoaded == true) {
            dao.latestBodyweightNow()?.grams
        } else {
            null
        }

        val previous = previousBests(exerciseId, now)
        val set = WorkSet.of(
            sessionId = sessionId,
            exerciseId = exerciseId,
            position = dao.nextPosition(sessionId),
            weightGrams = weightGrams,
            reps = reps,
            kind = kind,
            bodyweightGrams = bodyweight,
            rpe = rpe,
            holdSeconds = holdSeconds,
            completedAt = now,
        )
        val id = dao.insertSet(set)

        val broken = Records.brokenBy(
            weightGrams = set.effectiveGrams,
            reps = reps,
            warmUp = kind == SetKind.WARMUP,
            previous = previous,
        )
        return LoggedSet(set.copy(id = id), Records.worthAnnouncing(broken, previous))
    }

    suspend fun previousBests(exerciseId: String, before: Long): PreviousBests {
        val rows = dao.bestsBefore(exerciseId, before)
        return PreviousBests(
            heaviestGrams = rows.maxOfOrNull { it.grams } ?: 0,
            estimatedGrams = rows.mapNotNull { it.e1rm }.maxOrNull() ?: 0,
            heaviestAtReps = rows.associate { it.reps to it.grams },
        )
    }

    suspend fun deleteSet(id: Long) = dao.deleteSet(id)

    /**
     * Edits a set, recomputing the two derived columns from the new values.
     *
     * Through [WorkSet.of] rather than `copy`, so an edit cannot leave a row whose stored
     * estimate belongs to the weight it used to have.
     */
    suspend fun editSet(existing: WorkSet, weightGrams: Int, reps: Int, kind: SetKind, rpe: Float?) {
        dao.updateSet(
            WorkSet.of(
                sessionId = existing.sessionId,
                exerciseId = existing.exerciseId,
                position = existing.position,
                weightGrams = weightGrams,
                reps = reps,
                kind = kind,
                bodyweightGrams = existing.bodyweightGrams,
                rpe = rpe,
                holdSeconds = existing.holdSeconds,
                completedAt = existing.completedAt,
                note = existing.note,
            ).copy(id = existing.id)
        )
    }

    suspend fun lastWorkingSet(exerciseId: String, excludingSession: Long): WorkSet? =
        dao.lastWorkingSet(exerciseId, excludingSession)

    // ---- Volume ----------------------------------------------------------------------

    /** A week of work, per muscle. The counting rules live in [Volume], not in SQL. */
    fun volumeForWeek(weekStart: Long): Flow<List<MuscleVolume>> =
        dao.volumeRows(weekStart, weekStart + 7 * Time.DAY_MS).map { rows ->
            Volume.aggregate(
                rows.map {
                    VolumeSet(
                        weightGrams = it.effectiveGrams,
                        reps = it.reps,
                        warmUp = it.kind == SetKind.WARMUP,
                        primaryMuscles = Exercise.split(it.primaryMuscles),
                        secondaryMuscles = Exercise.split(it.secondaryMuscles),
                    )
                }
            )
        }

    // ---- Steps -----------------------------------------------------------------------

    /**
     * Turns a batch of sensor readings into steps in days and hours, transactionally.
     *
     * The reconciliation itself is a pure function tested without a device; this is only
     * the part that needs storage — reading where it got to, deciding which hour each
     * delta belongs in, and committing both halves together.
     */
    suspend fun recordSteps(readings: List<StepReading>, goal: Int): Int {
        val stored = dao.stepCursor()
        val cursor = stored?.let { StepCursor(it.counter, it.elapsedNanos) }
        val advance = StepReconciler.advance(cursor, readings) ?: return 0

        val buckets = advance.deltas.map {
            Triple(Time.startOfDayFor(it.atMillis), Time.hourOf(it.atMillis), it.steps)
        }
        dao.commitSteps(
            buckets = buckets,
            goal = goal,
            cursor = StepCursorRow(
                counter = advance.cursor.counter,
                elapsedNanos = advance.cursor.elapsedNanos,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return advance.deltas.sumOf { it.steps }
    }

    companion object {
        @Volatile private var instance: PlyRepository? = null

        fun get(context: Context): PlyRepository = instance ?: synchronized(this) {
            instance ?: PlyRepository(
                context.applicationContext,
                PlyDatabase.get(context).dao(),
                Settings(context.applicationContext),
            ).also { instance = it }
        }
    }
}
