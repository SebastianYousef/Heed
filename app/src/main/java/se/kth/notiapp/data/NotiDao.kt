package se.kth.notiapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NotificationRecord): Long

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 500): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notifications WHERE decision = :decision ORDER BY postedAt DESC LIMIT :limit")
    fun observeByDecision(decision: Decision, limit: Int = 500): Flow<List<NotificationRecord>>

    @Query("SELECT * FROM notifications WHERE id = :id")
    fun observeOne(id: Long): Flow<NotificationRecord?>

    @Query("SELECT * FROM notifications WHERE sbnKey = :key ORDER BY postedAt DESC LIMIT 1")
    suspend fun findByKey(key: String): NotificationRecord?

    /** Everything filed silently since the last digest — the raw material for a summary. */
    @Query(
        """
        SELECT * FROM notifications
        WHERE decision = 'SUPPRESSED' AND digestId IS NULL AND postedAt >= :since
        ORDER BY packageName, postedAt
        """
    )
    suspend fun pendingForDigest(since: Long): List<NotificationRecord>

    @Query("UPDATE notifications SET digestId = :digestId WHERE id IN (:ids)")
    suspend fun attachToDigest(ids: List<Long>, digestId: Long)

    @Query("UPDATE notifications SET feedback = :feedback, feedbackAt = :at WHERE id = :id")
    suspend fun setFeedback(id: Long, feedback: Feedback, at: Long = System.currentTimeMillis())

    @Query("UPDATE notifications SET feedback = :feedback, feedbackAt = :at WHERE sbnKey = :key AND feedback = 'NONE'")
    suspend fun setFeedbackByKey(key: String, feedback: Feedback, at: Long = System.currentTimeMillis())

    @Query("UPDATE notifications SET seen = 1 WHERE id = :id")
    suspend fun markSeen(id: Long)

    /** Training set: everything the user has reacted to, newest first. */
    @Query("SELECT * FROM notifications WHERE feedback != 'NONE' ORDER BY feedbackAt DESC LIMIT :limit")
    suspend fun trainingExamples(limit: Int = 2000): List<NotificationRecord>

    @Query("SELECT COUNT(*) FROM notifications WHERE decision = 'SUPPRESSED' AND digestId IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM notifications WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    // --- app policies ---

    @Upsert
    suspend fun upsertPolicy(policy: AppPolicyRecord)

    @Query("SELECT * FROM app_policies WHERE packageName = :pkg")
    suspend fun policyFor(pkg: String): AppPolicyRecord?

    @Query("SELECT * FROM app_policies ORDER BY (alertedCount + suppressedCount) DESC")
    fun observePolicies(): Flow<List<AppPolicyRecord>>

    @Query("UPDATE app_policies SET alertedCount = alertedCount + :alerted, suppressedCount = suppressedCount + :suppressed, lastSeenAt = :at WHERE packageName = :pkg")
    suspend fun bumpCounts(pkg: String, alerted: Int, suppressed: Int, at: Long)

    // --- digests ---

    @Insert
    suspend fun insertDigest(digest: DigestRecord): Long

    @Query("SELECT * FROM digests ORDER BY createdAt DESC LIMIT :limit")
    fun observeDigests(limit: Int = 50): Flow<List<DigestRecord>>

    @Query("UPDATE digests SET delivered = 1 WHERE id = :id")
    suspend fun markDigestDelivered(id: Long)

    // --- model ---

    @Upsert
    suspend fun saveModel(state: ModelState)

    @Query("SELECT * FROM model_state WHERE id = 1")
    suspend fun loadModel(): ModelState?
}
