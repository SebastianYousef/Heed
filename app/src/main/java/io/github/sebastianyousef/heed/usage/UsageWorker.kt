package io.github.sebastianyousef.heed.usage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebastianyousef.heed.data.HeedRepository
import java.util.concurrent.TimeUnit

/**
 * Pulls foreground sessions out of the system on a lazy schedule.
 *
 * Deliberately not a service watching the foreground app. UsageStatsManager keeps its
 * events for days, so everything can be reconstructed after the fact — an app about
 * reducing the cost of your phone has no business holding a wakelock to do it.
 */
class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = HeedRepository.get(applicationContext)
        UsageTracker(applicationContext, repo).ingest()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "heed_usage"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageWorker>(20, TimeUnit.MINUTES).build(),
            )
        }
    }
}
