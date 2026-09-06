package io.github.sebastianyousef.ply.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A movement, and everything the app needs to know about it to count it.
 *
 * 876 of these are vendored from [free-exercise-db](https://github.com/yuhonas/free-exercise-db),
 * which is public domain — so it can be copied into the APK with nothing owed and nothing
 * to fetch, and the app works in a basement with no signal. The alternative shape, an app
 * that downloads its exercise library, is what forces the INTERNET permission that this
 * whole project is arranged around not having.
 *
 * The rows are held in SQLite rather than parsed from the asset on demand, and the reason
 * is the muscle aggregation. Volume per muscle per week is a `GROUP BY` over sets joined
 * to their exercises; with the library outside the database that join has to happen in
 * Kotlin, which means loading every set into the heap to answer a question SQLite can
 * answer without materialising a row. The previous app made exactly that mistake and its
 * statistics screen loaded four thousand rows on every change.
 *
 * The id is the dataset's own slug, kept rather than replaced with an autoincrement, so
 * that re-seeding a later version of the dataset updates rows in place instead of
 * orphaning every set that pointed at them.
 */
@Entity(
    tableName = "exercises",
    indices = [Index("name"), Index("archived")],
)
data class Exercise(
    @PrimaryKey val id: String,

    val name: String,

    /** push, pull, static — or null, which the dataset does contain. */
    val force: String? = null,

    /** beginner, intermediate, expert. */
    val level: String = "intermediate",

    /** compound or isolation. Used to order the picker: compounds first. */
    val mechanic: String? = null,

    /** barbell, dumbbell, machine, cable, body only, … */
    val equipment: String? = null,

    /** strength, cardio, stretching, powerlifting, olympic weightlifting, … */
    val category: String = "strength",

    /**
     * Comma separated, from a closed vocabulary of seventeen names.
     *
     * A string rather than a join table, and this is the one place that trade is worth
     * making: the list is never queried *by* muscle from SQL — the volume aggregation
     * reads sets and their exercises and does the fan-out in one pass — and a join table
     * would mean two more tables, two more migrations and a three-way join to answer
     * every question about a set.
     */
    val primaryMuscles: String = "",

    val secondaryMuscles: String = "",

    /** One step per line. The reason the dataset is worth its 800 KB. */
    val instructions: String = "",

    /** Made by the user rather than shipped. Never overwritten by re-seeding. */
    val custom: Boolean = false,

    /**
     * Hidden from the picker without being deleted.
     *
     * Deleting is not offered, because a set points at an exercise and history that loses
     * the name of what was done is worse than a long picker. Archiving solves the actual
     * complaint, which is that 876 exercises is too many to scroll.
     */
    val archived: Boolean = false,

    /**
     * Whether the lifter's own weight is part of the load.
     *
     * True for pull-ups and dips, where "+10 kg" means bodyweight plus ten and a log that
     * records ten is not a record of anything. Seeded from the dataset's equipment field
     * and editable, because the dataset calls an assisted pull-up "machine" and it is not.
     */
    val bodyweightLoaded: Boolean = false,

    /**
     * How long to rest after a set of this, in seconds, or null to use the global default.
     *
     * Per-exercise because the right answer differs by an order of magnitude — twenty
     * seconds after a set of curls, five minutes after a heavy squat — and a single global
     * timer is one that is wrong for one of them and gets ignored for both.
     */
    val restSeconds: Int? = null,

    /**
     * Which version of the vendored dataset wrote this row.
     *
     * So a later release can tell a row it shipped from one the user edited, and re-seed
     * the first without discarding the second.
     */
    @ColumnInfo(defaultValue = "1")
    val seedVersion: Int = 1,
) {
    val primary: List<String> get() = split(primaryMuscles)
    val secondary: List<String> get() = split(secondaryMuscles)
    val steps: List<String> get() = instructions.split('\n').filter { it.isNotBlank() }

    companion object {
        fun split(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        fun join(values: List<String>): String = values.joinToString(",")

        /**
         * The id given to an exercise somebody adds themselves.
         *
         * Prefixed so it can never collide with a dataset slug, which is what would let a
         * future re-seed overwrite something the user wrote.
         */
        fun customId(): String = "custom-" + java.util.UUID.randomUUID()
    }
}
