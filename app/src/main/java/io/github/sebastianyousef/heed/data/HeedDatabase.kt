package io.github.sebastianyousef.heed.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationRecord::class,
        AppPolicyRecord::class,
        DigestRecord::class,
        ModelState::class,
        LiveChannelRecord::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HeedDatabase : RoomDatabase() {
    abstract fun dao(): HeedDao

    companion object {

        /**
         * Adds content-hash dedupe and the live-update channel table. Written out rather
         * than falling back to destructive migration because the model weights live in
         * this database — wiping it would throw away everything the app has learned.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN contentHash INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notifications ADD COLUMN updateCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS live_channels (
                        packageName TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        detectedAt INTEGER NOT NULL,
                        burstSize INTEGER NOT NULL,
                        PRIMARY KEY(packageName, channelId)
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds content scrubbing: when the text went, and what shape it had. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN redactedAt INTEGER")
                db.execSQL("ALTER TABLE notifications ADD COLUMN textShape TEXT")
            }
        }

        @Volatile private var instance: HeedDatabase? = null

        fun get(context: Context): HeedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HeedDatabase::class.java,
                "heed.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
