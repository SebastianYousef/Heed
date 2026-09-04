package io.github.sebastianyousef.heed.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.HeedRepository

/** Handles the inline "not important" action on a re-raised alert. */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_NOISE = "io.github.sebastianyousef.heed.MARK_NOISE"

        /** The "turn it off" button on the banking-app offer. */
        const val ACTION_PAUSE_SCREEN_ACCESS = "io.github.sebastianyousef.heed.PAUSE_SCREEN_ACCESS"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAUSE_SCREEN_ACCESS) {
            io.github.sebastianyousef.heed.focus.ScrollWatcherService.pause()
            Notifier(context.applicationContext).cancelScreenAccessNotice()
            return
        }
        if (intent.action != ACTION_MARK_NOISE) return
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (recordId < 0) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HeedRepository.get(appContext).recordFeedback(recordId, Feedback.MARKED_NOISE)
                if (notificationId != -1) Notifier(appContext).cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }
}
