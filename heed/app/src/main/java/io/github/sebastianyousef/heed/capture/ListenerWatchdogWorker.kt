package io.github.sebastianyousef.heed.capture

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.sebastianyousef.heed.data.HeedRepository
import java.util.concurrent.TimeUnit

/**
 * Puts the listener back when Android takes it away.
 *
 * A NotificationListenerService can be unbound for reasons that have nothing to do with
 * the user: low memory, the app being updated, the service crashing. The failure is
 * silent — notifications simply stop arriving while the app looks perfectly healthy,
 * which is the worst way for something like this to break, because you only find out by
 * missing something.
 *
 * requestRebind() asks the system to bind us again, and is the documented remedy. It is
 * called both from onListenerDisconnected and from here, on a schedule, to cover the case
 * where the process died outright and there was nobody left to notice.
 *
 * On AOSP-derived systems, GrapheneOS included, unbinding is rare. On skinned Android —
 * Samsung, Xiaomi, and friends — aggressive battery management makes it routine.
 */
class ListenerWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = HeedRepository.get(applicationContext)

        if (HeedListenerService.isEnabled(applicationContext) && !HeedListenerService.active) {
            repo.setListenerConnected(false)
            requestRebind(applicationContext)
        }

        // Also a good moment to settle anything the last process death left mid-flight.
        repo.resolveOrphanedHolds()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "listener_watchdog"

        fun requestRebind(context: Context) {
            NotificationListenerService.requestRebind(
                ComponentName(context, HeedListenerService::class.java)
            )
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ListenerWatchdogWorker>(
                30, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
