package se.kth.notiapp.capture

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import se.kth.notiapp.data.NotificationRecord
import java.util.concurrent.ConcurrentHashMap

object NotificationMapper {

    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * Things we should never touch. Cancelling a media transport control or a running
     * foreground service notification does not just annoy the user, it can break the app
     * that posted it — Android reposts foreground-service notifications and some players
     * lose their controls entirely.
     */
    fun isIgnorable(context: Context, sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == context.packageName) return true

        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true

        // Media transport controls.
        val template = n.extras?.getString(Notification.EXTRA_TEMPLATE)
        if (template != null && template.endsWith("MediaStyle")) return true

        // Nothing to classify.
        val hasText = !n.extras?.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !n.extras?.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
        return !hasText
    }

    fun toRecord(
        context: Context,
        sbn: StatusBarNotification,
        systemImportance: Int,
    ): NotificationRecord {
        val n = sbn.notification
        val e = n.extras

        return NotificationRecord(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = e?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = e?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = e?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = e?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            category = n.category,
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null,
            systemImportance = systemImportance,
            postedAt = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            hasPerson = hasPerson(sbn),
        )
    }

    /**
     * Whether the notification names a human. A strong relevance signal: apps attach
     * People to real conversations and almost never to marketing.
     */
    private fun hasPerson(sbn: StatusBarNotification): Boolean {
        val e = sbn.notification.extras ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val people = e.getParcelableArrayList<android.os.Parcelable>(Notification.EXTRA_PEOPLE_LIST)
            if (!people.isNullOrEmpty()) return true
        }
        @Suppress("DEPRECATION")
        val legacy = e.getStringArray(Notification.EXTRA_PEOPLE)
        if (!legacy.isNullOrEmpty()) return true

        val template = e.getString(Notification.EXTRA_TEMPLATE)
        return template != null && template.endsWith("MessagingStyle")
    }

    fun appLabel(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
