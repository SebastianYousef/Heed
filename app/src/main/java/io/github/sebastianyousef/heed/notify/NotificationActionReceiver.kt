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

        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
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
