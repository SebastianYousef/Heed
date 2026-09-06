package io.github.sebastianyousef.ply.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An hour of walking.
 *
 * The buckets are the truth and the day total is `SUM` over them, rather than both being
 * stored and kept in step. Two stored copies of one number is how a total and its own
 * breakdown come to disagree, and when they do there is no way to tell which is right —
 * the previous app shipped a statistic that was hard-coded to zero for months for exactly
 * this kind of reason.
 *
 * Twenty-four rows per day is nothing to sum, and it is the resolution that makes "when do
 * I actually walk" answerable.
 */
@Entity(
    tableName = "step_buckets",
    primaryKeys = ["day", "hour"],
    indices = [Index("day")],
)
data class StepBucket(
    /** Midnight at the start of the day, in the device's own zone. */
    val day: Long,
    /** 0..23. */
    val hour: Int,
    val steps: Int,
)

/**
 * The goal that was in force on a day.
 *
 * Stored per day rather than read from settings when drawing history, so that raising your
 * goal does not retroactively turn a month of days you met into days you missed. The row
 * is written the first time a day gets any steps at all.
 */
@Entity(tableName = "step_days")
data class StepDay(
    @PrimaryKey val day: Long,
    val goal: Int,
)

/**
 * Where the step counter had got to when it was last read.
 *
 * In the database rather than in DataStore, because it has to be written in the same
 * transaction as the buckets it produced. Split across two stores, a crash between the two
 * writes either double-counts a batch or drops it, and there is no way to detect which
 * happened afterwards.
 *
 * One row, forever. See [io.github.sebastianyousef.ply.move.StepReconciler] for what the
 * two numbers mean and why the elapsed clock is the one that detects a reboot.
 */
@Entity(tableName = "step_cursor")
data class StepCursorRow(
    @PrimaryKey val id: Int = 0,
    val counter: Long,
    val elapsedNanos: Long,
    val updatedAt: Long,
)
