package io.github.sebastianyousef.heed.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.sebastianyousef.heed.MainActivity
import io.github.sebastianyousef.heed.R
import io.github.sebastianyousef.heed.capture.HoldBuffer
import io.github.sebastianyousef.heed.data.DigestRecord

/**
 * The only thing on the phone allowed to make noise.
 *
 * In quiet-source mode every other app has been turned down to silent, so a notification
 * we decide is worth showing has to be re-raised by us — with our own channel, our own
 * sound, and a "not important" action that feeds straight back into the classifier.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_ALERT = "heed_alert"
        const val CHANNEL_DIGEST = "heed_digest"
        private const val DIGEST_ID = 1_000_001
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Things that matter",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications Heed judged worth interrupting you for."
                enableVibration(true)
            }
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIGEST,
                "Summaries",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Periodic digests of everything that was filtered out."
                setSound(null, null)
            }
        )
    }

    /**
     * Re-raise a held notification as our own alert.
     *
     * [alertAgain] is false when this is an edit to something already on screen — the
     * alert refreshes silently instead of ringing a second time for the same thing.
     */
    fun raise(held: HoldBuffer.Held, recordId: Long, alertAgain: Boolean = true) {
        val record = held.record
        val id = record.sbnKey.hashCode()

        val noise = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_NOISE
                putExtra(NotificationActionReceiver.EXTRA_RECORD_ID, recordId)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_heed)
            .setContentTitle(record.title ?: record.appLabel)
            .setContentText(record.text)
            .setSubText(record.appLabel)
            .setCategory(record.category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(record.postedAt)
            .setOnlyAlertOnce(!alertAgain)
            .addAction(0, "Not important", noise)

        record.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }

        // Opening our alert should land the user in the app the notification came from,
        // not in Heed. Falls back to our inbox if the PendingIntent has expired.
        builder.setContentIntent(held.contentIntent ?: openInbox(id))

        postIfAllowed(id, builder.build())
    }

    fun postDigest(digest: DigestRecord) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_heed)
            .setContentTitle("${digest.notificationCount} filtered while you were away")
            .setContentText(digest.summary.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(digest.summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openInbox(DIGEST_ID))
        postIfAllowed(DIGEST_ID, builder.build())
    }

    fun cancel(id: Int) = manager.cancel(id)

    private fun openInbox(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postIfAllowed(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted yet; onboarding will ask.
        }
    }
}
