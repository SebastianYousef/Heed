package se.kth.notiapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [NotificationRecord::class, AppPolicyRecord::class, DigestRecord::class, ModelState::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NotiDatabase : RoomDatabase() {
    abstract fun dao(): NotiDao

    companion object {
        @Volatile private var instance: NotiDatabase? = null

        fun get(context: Context): NotiDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotiDatabase::class.java,
                "noti.db",
            ).build().also { instance = it }
        }
    }
}
