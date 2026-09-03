package io.github.sebastianyousef.heed.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HeedDao {

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

    /**
     * An in-place update to a notification we already hold: same content, newer post
     * time. Nothing visible changed, so only the counters move.
     */
    @Query("UPDATE notifications SET updateCount = updateCount + 1, postedAt = :postedAt WHERE id = :id")
    suspend fun bumpUpdate(id: Long, postedAt: Long)

    /** The notification changed under the same key — overwrite the row rather than add one. */
    @Query(
        """
        UPDATE notifications SET
            title = :title, text = :text, bigText = :bigText, subText = :subText,
            contentHash = :contentHash, postedAt = :postedAt,
            score = :score, scoreReason = :scoreReason, decision = :decision,
            updateCount = updateCount + 1
        WHERE id = :id
        """
    )
    suspend fun updateInPlace(
        id: Long,
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        contentHash: Int,
        postedAt: Long,
        score: Float,
        scoreReason: String,
        decision: Decision,
    )

    @Query("UPDATE notifications SET decision = :decision, score = :score, scoreReason = :reason, capturePath = :path WHERE id = :id")
    suspend fun resolveHeld(id: Long, decision: Decision, score: Float, reason: String, path: CapturePath)

    /**
     * Held notifications orphaned by the process dying inside the hold window. They never
     * alerted anyone, so filing them is the correct resolution.
     */
    @Query(
        """
        UPDATE notifications
        SET decision = 'SUPPRESSED',
            scoreReason = scoreReason || ' · filed unjudged after Heed restarted'
        WHERE decision = 'HELD' AND postedAt < :before
        """
    )
    suspend fun resolveStaleHeld(before: Long): Int

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

    // --- retention ---

    /** Rows old enough to have their text scrubbed but that still hold it. */
    @Query("SELECT * FROM notifications WHERE redactedAt IS NULL AND postedAt < :before")
    suspend fun scrubbable(before: Long): List<NotificationRecord>

    @Query(
        """
        UPDATE notifications
        SET title = NULL, text = NULL, bigText = NULL, subText = NULL,
            textShape = :shape, redactedAt = :at
        WHERE id = :id
        """
    )
    suspend fun scrub(id: Long, shape: String, at: Long)

    @Query("SELECT COUNT(*) FROM notifications WHERE redactedAt IS NOT NULL")
    fun observeScrubbedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE redactedAt IS NULL")
    fun observeReadableCount(): Flow<Int>

    // --- one-shot reads, for the data export ---

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC LIMIT :limit")
    suspend fun allRecords(limit: Int): List<NotificationRecord>

    @Query("SELECT * FROM app_policies ORDER BY (alertedCount + suppressedCount) DESC")
    suspend fun allPolicies(): List<AppPolicyRecord>

    @Query("SELECT * FROM digests ORDER BY createdAt DESC LIMIT :limit")
    suspend fun allDigests(limit: Int): List<DigestRecord>

    // --- app policies ---

    @Upsert
    suspend fun upsertPolicy(policy: AppPolicyRecord)

    @Query("SELECT * FROM app_policies WHERE packageName = :pkg")
    suspend fun policyFor(pkg: String): AppPolicyRecord?

    @Query("SELECT * FROM app_policies ORDER BY (alertedCount + suppressedCount) DESC")
    fun observePolicies(): Flow<List<AppPolicyRecord>>

    @Query("UPDATE app_policies SET alertedCount = alertedCount + :alerted, suppressedCount = suppressedCount + :suppressed, lastSeenAt = :at WHERE packageName = :pkg")
    suspend fun bumpCounts(pkg: String, alerted: Int, suppressed: Int, at: Long)

    // --- live-update channels ---

    @Upsert
    suspend fun markLiveChannel(record: LiveChannelRecord)

    @Query("SELECT * FROM live_channels")
    suspend fun liveChannels(): List<LiveChannelRecord>

    @Query("SELECT * FROM live_channels ORDER BY detectedAt DESC")
    fun observeLiveChannels(): Flow<List<LiveChannelRecord>>

    @Query("DELETE FROM live_channels WHERE packageName = :pkg AND channelId = :channelId")
    suspend fun clearLiveChannel(pkg: String, channelId: String)

    /** Clear rows already captured from a channel before we recognised it as a live display. */
    @Query("DELETE FROM notifications WHERE packageName = :pkg AND channelId = :channelId")
    suspend fun deleteFromChannel(pkg: String, channelId: String)

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
