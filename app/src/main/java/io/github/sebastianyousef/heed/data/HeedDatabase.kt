package io.github.sebastianyousef.heed.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.LearnedSurface
import io.github.sebastianyousef.heed.usage.ScrollSpan
import io.github.sebastianyousef.heed.usage.SessionRecord

@Database(
    entities = [
        NotificationRecord::class,
        AppPolicyRecord::class,
        DigestRecord::class,
        ModelState::class,
        LiveChannelRecord::class,
        SessionRecord::class,
        ScrollSpan::class,
        FocusRule::class,
        LearnedSurface::class,
    ],
    version = 8,
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

        /** Adds foreground sessions, and their attribution back to a notification. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        triggerNotificationId INTEGER,
                        scrollEvents INTEGER,
                        longestScrollBurstMs INTEGER,
                        trainedOn INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_startedAt ON sessions(startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_packageName ON sessions(packageName)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sessions_triggerNotificationId " +
                        "ON sessions(triggerNotificationId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scroll_spans (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        packageName TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL,
                        events INTEGER NOT NULL,
                        longestBurstMs INTEGER NOT NULL,
                        consumed INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scroll_spans_startedAt ON scroll_spans(startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scroll_spans_consumed ON scroll_spans(consumed)")
            }
        }

        /** Adds per-app focus rules: what Heed may do about each app. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS focus_rules (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        appLabel TEXT NOT NULL,
                        mode TEXT NOT NULL DEFAULT 'OFF',
                        scrollBudgetEvents INTEGER NOT NULL DEFAULT 4,
                        dailyScrollSeconds INTEGER NOT NULL DEFAULT 0,
                        dailyUsageSeconds INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds launch limits, per-app detection mode, and taught screens. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_rules ADD COLUMN dailyLaunchLimit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE focus_rules ADD COLUMN detection TEXT NOT NULL DEFAULT 'BEHAVIOURAL'")
                db.execSQL("ALTER TABLE focus_rules ADD COLUMN fromPreset INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learned_surfaces (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        packageName TEXT NOT NULL,
                        label TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        block INTEGER NOT NULL,
                        capturedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learned_surfaces_packageName " +
                        "ON learned_surfaces(packageName)"
                )
            }
        }

        /**
         * Adds the per-app grayscale switch.
         *
         * Grayscale needed a home somewhere, and the rule row is the right one: it is
         * per-app, it is a thing Heed may do about an app, and putting it here means the
         * same editor that sets a limit can set it.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_rules ADD COLUMN grayscale INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the sender identity. Nullable and unbackfilled: history keeps working, and
         * rows captured before the upgrade simply have no sender to learn from.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN conversationId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notifications_conversationId " +
                        "ON notifications(conversationId)"
                )
            }
        }

        @Volatile private var instance: HeedDatabase? = null

        fun get(context: Context): HeedDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HeedDatabase::class.java,
                "heed.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
        }
    }
}
