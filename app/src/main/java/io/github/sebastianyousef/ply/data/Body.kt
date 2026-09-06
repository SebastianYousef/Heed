package io.github.sebastianyousef.ply.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What you weighed on a given day.
 *
 * Keyed by the day rather than by an autoincrement, so weighing yourself twice replaces
 * rather than accumulates. Bodyweight is noisy enough — two kilos between morning and
 * evening — that two readings from one day are not two data points, they are one data
 * point and a measurement error, and a chart that plots both makes the noise look like
 * signal.
 *
 * Grams, like every other weight in the app, for the same reason: [io.github.sebastianyousef.ply.train.Load].
 */
@Entity(tableName = "bodyweight")
data class Bodyweight(
    /** Midnight at the start of the day, in the device's own zone. */
    @PrimaryKey val day: Long,

    val grams: Int,

    /** The actual moment, kept so "weighed this morning" can be distinguished from "at some point". */
    val recordedAt: Long = System.currentTimeMillis(),
)

/**
 * Where a tape measure was put.
 *
 * A closed list rather than free text, because the value of a measurement is entirely in
 * comparing it to the same measurement later, and free text produces "left arm", "Left
 * Arm" and "arm (L)" as three separate series of one reading each.
 */
enum class MeasurementSite(val label: String) {
    NECK("Neck"),
    CHEST("Chest"),
    WAIST("Waist"),
    HIPS("Hips"),
    THIGH("Thigh"),
    CALF("Calf"),
    UPPER_ARM("Upper arm"),
    FOREARM("Forearm"),
}

/**
 * A circumference, in millimetres.
 *
 * Millimetres for the same reason weights are grams: a tape reads to the half centimetre,
 * and storing 34.5 cm as a float is a way for two equal measurements to compare unequal.
 *
 * No photos. They are the most sensitive thing such an app could hold and they contribute
 * nothing to any number in it, so the honest trade is not to take on the liability. An
 * app with no network permission still sits in a file system that other things can be
 * granted access to, and "we never upload them" is a weaker promise than not having them.
 */
@Entity(
    tableName = "measurements",
    primaryKeys = ["day", "site"],
    indices = [Index("site")],
)
data class Measurement(
    val day: Long,
    val site: String,
    val millimetres: Int,
    val recordedAt: Long = System.currentTimeMillis(),
)
