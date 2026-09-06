package io.github.sebastianyousef.ply.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The database, and the promise attached to it: nothing in here is ever thrown away to
 * make an upgrade easier.
 *
 * A training history cannot be regenerated. It is not a cache of something that exists
 * elsewhere, there is no server holding a copy, and a year of it represents a year of
 * turning up. So `fallbackToDestructiveMigration` is not used, will not be used, and every
 * migration is written by hand and reviewed against the schema JSON it produces — which is
 * why [exportSchema] is on and the JSON is committed.
 *
 * There are no migrations yet because there has been no release to migrate from. The first
 * one to be written goes in the companion beside a KDoc saying what it adds and why there.
 */
@Database(
    entities = [
        Exercise::class,
        Session::class,
        WorkSet::class,
        Routine::class,
        RoutineItem::class,
        Bodyweight::class,
        Measurement::class,
        StepBucket::class,
        StepDay::class,
        StepCursorRow::class,
    ],
    version = PlyDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PlyDatabase : RoomDatabase() {

    abstract fun dao(): PlyDao

    companion object {

        /**
         * The on-disk format, so the About screen can print it.
         *
         * Read by the annotation above rather than repeated in it, so that the two cannot
         * drift — a migration that forgets to raise this fails at build time instead of
         * mislabelling a bug report.
         */
        const val SCHEMA_VERSION = 1

        @Volatile private var instance: PlyDatabase? = null

        fun get(context: Context): PlyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PlyDatabase::class.java,
                "ply.db",
            ).build().also { instance = it }
        }
    }
}
