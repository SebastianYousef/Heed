package io.github.sebastianyousef.heed.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Forgets, on a schedule, in two stages.
 *
 * After the content window the words are scrubbed but the row remains, so the inbox still
 * shows that Slack interrupted you eleven times last month and what Heed decided each
 * time. After the longer record window the row goes too.
 *
 * Neither stage affects what the app has learned. That is the whole design: the model is
 * trained the instant you react and the weights are stored separately, so the text is
 * only ever evidence you might want to reread — never the thing the classifier depends on.
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = HeedRepository.get(applicationContext)
        repo.scrubOldContent()
        repo.pruneOldRecords()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "heed_retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
