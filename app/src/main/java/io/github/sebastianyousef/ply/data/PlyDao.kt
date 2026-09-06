package io.github.sebastianyousef.ply.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room stores enums as their name; nothing else here needs converting. */
class Converters {
    @TypeConverter fun kindToString(kind: SetKind): String = kind.name
    @TypeConverter fun stringToKind(value: String): SetKind =
        runCatching { SetKind.valueOf(value) }.getOrDefault(SetKind.WORKING)
}

/** A set with the two exercise fields the UI always needs beside it. */
data class SetWithExercise(
    val id: Long,
    val sessionId: Long,
    val exerciseId: String,
    val exerciseName: String,
    val bodyweightLoaded: Boolean,
    val position: Int,
    val weightGrams: Int,
    val reps: Int,
    val kind: SetKind,
    val bodyweightGrams: Int?,
    val effectiveGrams: Int,
    val e1rmGrams: Int?,
    val rpe: Float?,
    val holdSeconds: Int?,
    val completedAt: Long,
    val note: String?,
)

/** The best at each rep count, which is all three kinds of record in one query. */
data class BestAtReps(val reps: Int, val grams: Int, val e1rm: Int?)

/** Enough of a set to aggregate volume, with the muscles already joined on. */
data class VolumeRow(
    val weightGrams: Int,
    val effectiveGrams: Int,
    val reps: Int,
    val kind: SetKind,
    val primaryMuscles: String,
    val secondaryMuscles: String,
    val completedAt: Long,
)

/** A session and the two figures a history row shows without opening it. */
data class SessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val title: String,
    val setCount: Int,
    val workingSets: Int,
    val tonnageGrams: Long,
    val exerciseCount: Int,
)

/** A day and what was walked in it. */
data class DaySteps(val day: Long, val steps: Int, val goal: Int?)

@Dao
interface PlyDao {

    // ---- Exercises -------------------------------------------------------------------

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun exercise(id: String): Exercise?

    @Query("SELECT * FROM exercises WHERE archived = 0 ORDER BY name")
    fun exercises(): Flow<List<Exercise>>

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun exerciseCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExercises(exercises: List<Exercise>)

    @Upsert
    suspend fun upsertExercise(exercise: Exercise)

    /**
     * The exercises used most recently, for the top of the picker.
     *
     * Recency rather than frequency: what you did on Monday is a far better predictor of
     * what you are about to log than what you have done most over two years, and a
     * frequency list is dominated by whatever you did when you first got the app.
     */
    @Query(
        """
        SELECT e.* FROM exercises e
        WHERE e.archived = 0 AND EXISTS (SELECT 1 FROM sets WHERE exerciseId = e.id)
        ORDER BY (SELECT MAX(completedAt) FROM sets WHERE exerciseId = e.id) DESC
        LIMIT :limit
        """
    )
    fun recentExercises(limit: Int = 12): Flow<List<Exercise>>

    // ---- Sessions --------------------------------------------------------------------

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun openSession(): Flow<Session?>

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSessionNow(): Session?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun session(id: Long): Flow<Session?>

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    /**
     * The history list. Aggregated in SQLite rather than by loading every set and folding
     * in Kotlin, which is what the previous app did until its statistics screen was
     * materialising four thousand rows on every database change.
     */
    @Query(
        """
        SELECT s.id, s.startedAt, s.endedAt, s.title,
               COUNT(w.id) AS setCount,
               COALESCE(SUM(CASE WHEN w.kind = 'WORKING' THEN 1 ELSE 0 END), 0) AS workingSets,
               COALESCE(SUM(CASE WHEN w.kind = 'WORKING'
                                 THEN CAST(w.effectiveGrams AS INTEGER) * w.reps ELSE 0 END), 0) AS tonnageGrams,
               COUNT(DISTINCT w.exerciseId) AS exerciseCount
        FROM sessions s
        LEFT JOIN sets w ON w.sessionId = s.id
        WHERE s.endedAt IS NOT NULL
        GROUP BY s.id
        ORDER BY s.startedAt DESC
        LIMIT :limit
        """
    )
    fun sessionHistory(limit: Int = 100): Flow<List<SessionSummary>>

    // ---- Sets ------------------------------------------------------------------------

    @Query(
        """
        SELECT w.id, w.sessionId, w.exerciseId, e.name AS exerciseName,
               e.bodyweightLoaded, w.position, w.weightGrams, w.reps, w.kind,
               w.bodyweightGrams, w.effectiveGrams, w.e1rmGrams, w.rpe, w.holdSeconds,
               w.completedAt, w.note
        FROM sets w JOIN exercises e ON e.id = w.exerciseId
        WHERE w.sessionId = :sessionId
        ORDER BY w.position
        """
    )
    fun setsForSession(sessionId: Long): Flow<List<SetWithExercise>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM sets WHERE sessionId = :sessionId")
    suspend fun nextPosition(sessionId: Long): Int

    @Insert
    suspend fun insertSet(set: WorkSet): Long

    @Update
    suspend fun updateSet(set: WorkSet)

    @Query("DELETE FROM sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("SELECT * FROM sets WHERE id = :id")
    suspend fun set(id: Long): WorkSet?

    /**
     * Everything needed to judge a record, in one grouped query.
     *
     * Deliberately `completedAt < :before` rather than "all of it": a set has to be judged
     * against what was true *before* it, or it beats itself and nothing is ever a record.
     */
    @Query(
        """
        SELECT reps, MAX(effectiveGrams) AS grams, MAX(e1rmGrams) AS e1rm
        FROM sets
        WHERE exerciseId = :exerciseId AND kind = 'WORKING' AND completedAt < :before
        GROUP BY reps
        """
    )
    suspend fun bestsBefore(exerciseId: String, before: Long): List<BestAtReps>

    /** The last working set of an exercise, which is what the logging screen prefills from. */
    @Query(
        """
        SELECT * FROM sets
        WHERE exerciseId = :exerciseId AND kind = 'WORKING' AND sessionId != :excludingSession
        ORDER BY completedAt DESC LIMIT 1
        """
    )
    suspend fun lastWorkingSet(exerciseId: String, excludingSession: Long): WorkSet?

    /** Every working set of an exercise, oldest first, for the estimate trend. */
    @Query(
        """
        SELECT * FROM sets
        WHERE exerciseId = :exerciseId AND kind = 'WORKING' AND e1rmGrams IS NOT NULL
        ORDER BY completedAt
        """
    )
    fun estimateTrend(exerciseId: String): Flow<List<WorkSet>>

    @Query(
        """
        SELECT w.weightGrams, w.effectiveGrams, w.reps, w.kind,
               e.primaryMuscles, e.secondaryMuscles, w.completedAt
        FROM sets w JOIN exercises e ON e.id = w.exerciseId
        WHERE w.completedAt >= :from AND w.completedAt < :to
        """
    )
    fun volumeRows(from: Long, to: Long): Flow<List<VolumeRow>>

    // ---- Routines --------------------------------------------------------------------

    @Query("SELECT * FROM routines ORDER BY position, name")
    fun routines(): Flow<List<Routine>>

    @Query("SELECT * FROM routine_items WHERE routineId = :routineId ORDER BY position")
    suspend fun routineItems(routineId: Long): List<RoutineItem>

    @Query("SELECT * FROM routine_items WHERE routineId = :routineId ORDER BY position")
    fun routineItemsFlow(routineId: Long): Flow<List<RoutineItem>>

    @Insert
    suspend fun insertRoutine(routine: Routine): Long

    @Update
    suspend fun updateRoutine(routine: Routine)

    @Delete
    suspend fun deleteRoutine(routine: Routine)

    @Query("UPDATE routines SET lastUsedAt = :at WHERE id = :id")
    suspend fun touchRoutine(id: Long, at: Long)

    @Upsert
    suspend fun upsertRoutineItem(item: RoutineItem)

    @Query("DELETE FROM routine_items WHERE id = :id")
    suspend fun deleteRoutineItem(id: Long)

    // ---- Body ------------------------------------------------------------------------

    @Upsert
    suspend fun upsertBodyweight(entry: Bodyweight)

    @Query("SELECT * FROM bodyweight ORDER BY day DESC LIMIT 1")
    fun latestBodyweight(): Flow<Bodyweight?>

    @Query("SELECT * FROM bodyweight ORDER BY day DESC LIMIT 1")
    suspend fun latestBodyweightNow(): Bodyweight?

    @Query("SELECT * FROM bodyweight WHERE day >= :from ORDER BY day")
    fun bodyweightSince(from: Long): Flow<List<Bodyweight>>

    @Upsert
    suspend fun upsertMeasurement(measurement: Measurement)

    @Query("SELECT * FROM measurements WHERE day >= :from ORDER BY day")
    fun measurementsSince(from: Long): Flow<List<Measurement>>

    // ---- Export ----------------------------------------------------------------------
    //
    // Deliberately not Flows and deliberately unbounded: an export is a one-off read of
    // everything, and paging it would only make it possible to produce a partial file that
    // still looks complete.

    @Query("SELECT * FROM sessions ORDER BY startedAt")
    suspend fun allSessions(): List<Session>

    @Query("SELECT * FROM sets WHERE sessionId = :sessionId ORDER BY position")
    suspend fun setsIn(sessionId: Long): List<WorkSet>

    @Query("SELECT * FROM bodyweight ORDER BY day")
    suspend fun allBodyweight(): List<Bodyweight>

    @Query("SELECT * FROM measurements ORDER BY day")
    suspend fun allMeasurements(): List<Measurement>

    @Query("SELECT * FROM step_buckets ORDER BY day, hour")
    suspend fun allStepBuckets(): List<StepBucket>

    @Query("SELECT * FROM exercises WHERE custom = 1 ORDER BY name")
    suspend fun customExercises(): List<Exercise>

    // ---- Steps -----------------------------------------------------------------------

    @Query("SELECT * FROM step_cursor WHERE id = 0")
    suspend fun stepCursor(): StepCursorRow?

    @Upsert
    suspend fun upsertStepCursor(cursor: StepCursorRow)

    @Query(
        """
        INSERT INTO step_buckets (day, hour, steps) VALUES (:day, :hour, :steps)
        ON CONFLICT(day, hour) DO UPDATE SET steps = steps + :steps
        """
    )
    suspend fun addSteps(day: Long, hour: Int, steps: Int)

    @Query("INSERT OR IGNORE INTO step_days (day, goal) VALUES (:day, :goal)")
    suspend fun ensureStepDay(day: Long, goal: Int)

    @Query(
        """
        SELECT b.day AS day, SUM(b.steps) AS steps, d.goal AS goal
        FROM step_buckets b LEFT JOIN step_days d ON d.day = b.day
        WHERE b.day >= :from AND b.day <= :to
        GROUP BY b.day ORDER BY b.day
        """
    )
    fun stepDays(from: Long, to: Long): Flow<List<DaySteps>>

    @Query("SELECT hour, steps FROM step_buckets WHERE day = :day ORDER BY hour")
    fun stepHours(day: Long): Flow<List<HourSteps>>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM step_buckets WHERE day = :day")
    suspend fun stepsOn(day: Long): Int

    /**
     * Writes a batch of deltas and the cursor that produced them in one transaction.
     *
     * The whole point: if this is interrupted, either all of the steps and the new cursor
     * land or none of them do. Split across two writes, a crash in between either counts a
     * batch twice or loses it, and nothing afterwards can tell which happened.
     */
    @Transaction
    suspend fun commitSteps(
        buckets: List<Triple<Long, Int, Int>>,
        goal: Int,
        cursor: StepCursorRow,
    ) {
        for ((day, hour, steps) in buckets) {
            ensureStepDay(day, goal)
            addSteps(day, hour, steps)
        }
        upsertStepCursor(cursor)
    }
}

/** One hour of a day's shape. */
data class HourSteps(val hour: Int, val steps: Int)
