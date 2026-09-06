package io.github.sebastianyousef.ply.move

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.widget.PlyWidget
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Reads the counter and writes down what has been walked since the last read.
 *
 * Fifteen minutes because that is WorkManager's floor, and it is the right floor: the
 * sensor is counting regardless, so the interval decides the resolution of the *shape* of
 * a day and nothing about its total. Under doze this will be deferred, sometimes by hours,
 * and the total still comes out right — which is the property that made an on-demand read
 * the correct design rather than a listener.
 *
 * The one thing a deferral does cost is a reboot in the gap: steps taken between the last
 * successful read and a shutdown are gone, because there is nothing left to read them
 * from. Fifteen minutes bounds that, and [BootReceiver] closes it from the other side.
 */
class StepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!StepSensor.permitted(applicationContext)) return Result.success()

        val reading = StepSensor.read(applicationContext) ?: return Result.retry()
        val repository = PlyRepository.get(applicationContext)
        val goal = repository.settings.stepGoal.first()
        val added = repository.recordSteps(listOf(reading), goal)

        if (added > 0) PlyWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "steps"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP rather than UPDATE, so that opening the app does not reset the
                // period and quietly postpone the next read every time.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** After a reboot, and when the app is opened, where waiting fifteen minutes is silly. */
        fun readNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<StepWorker>().build()
            )
        }
    }
}
