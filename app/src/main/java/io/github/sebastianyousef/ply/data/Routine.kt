package io.github.sebastianyousef.ply.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A plan: the exercises you mean to do, in order, with what you mean to do on them.
 *
 * A routine is a *starting point*, never a cage. Starting a session from one copies its
 * items onto the screen as targets and then gets out of the way — you can add an exercise
 * that is not in it, skip one that is, and change any weight without the routine noticing
 * or the session becoming "off plan". That is the difference between a plan and a program
 * that owns you, and it is the reason there is no notion of compliance anywhere in here.
 *
 * Progression is deliberately absent for now. Every automatic scheme — add 2.5 kg, double
 * progression, RPE-based autoregulation — is a rule about what you *should* lift next, and
 * a rule like that is wrong the first week you sleep badly. What the logging screen does
 * instead is show what you did last time, which is the same information without the
 * instruction. If a scheme is added later it will be per-routine and switchable off.
 */
@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val name: String,

    val note: String? = null,

    /** Hand-ordered, because the order you list your routines in is the order you do them. */
    val position: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),

    /** So the list can lead with the one you are actually on. */
    val lastUsedAt: Long? = null,
)

/**
 * One exercise inside a routine, with its targets.
 *
 * Targets are all nullable, and that is the point: a routine that says "bench press, 3
 * sets" is a legitimate and common thing to write down, and an app that demands a weight
 * for it forces you to invent one. Whatever is missing is filled from what you did last
 * time when the session starts.
 *
 * [targetReps] and [targetRepsMax] together express a range — 8, or 8 to 12 — because that
 * is how nearly every program is actually written, and collapsing it to a single number
 * loses the part that tells you when to add weight.
 */
@Entity(
    tableName = "routine_items",
    foreignKeys = [
        ForeignKey(
            entity = Routine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routineId")],
)
data class RoutineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val routineId: Long,

    val exerciseId: String,

    val position: Int,

    val targetSets: Int = 3,

    val targetReps: Int? = null,

    /** The top of a range. Null means [targetReps] is an exact figure rather than a floor. */
    val targetRepsMax: Int? = null,

    val targetWeightGrams: Int? = null,

    /** Overrides the exercise's own rest for this routine only. */
    val restSeconds: Int? = null,

    val note: String? = null,
)
