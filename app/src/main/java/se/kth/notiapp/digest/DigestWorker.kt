package se.kth.notiapp.digest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import se.kth.notiapp.data.DigestRecord
import se.kth.notiapp.data.NotiRepository
import se.kth.notiapp.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Builds and delivers the periodic summary of everything that was filtered out.
 *
 * Runs on WorkManager rather than an alarm so the OS can batch it with other wakeups —
 * an app whose whole premise is reducing interruption has no business being the thing
 * that drains the battery.
 */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = NotiRepository.get(applicationContext)
        val dao = repo.dao

        val settings = repo.settings.first()
        val windowMs = settings.digestIntervalHours * 3_600_000L
        val now = System.currentTimeMillis()
        val since = now - windowMs

        val pending = dao.pendingForDigest(since)
        if (pending.isEmpty()) return Result.success()

        val summary = Summarizers.best().summarize(pending)
        val digest = DigestRecord(
            createdAt = now,
            windowStart = since,
            windowEnd = now,
            notificationCount = pending.size,
            summary = summary,
        )
        val digestId = dao.insertDigest(digest)
        dao.attachToDigest(pending.map { it.id }, digestId)

        Notifier(applicationContext).postDigest(digest.copy(id = digestId))
        dao.markDigestDelivered(digestId)

        repo.pruneOldRecords()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "noti_digest"

        fun schedule(context: Context, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<DigestWorker>(
                intervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
