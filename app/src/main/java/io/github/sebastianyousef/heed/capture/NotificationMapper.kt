package io.github.sebastianyousef.heed.capture

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import io.github.sebastianyousef.heed.data.NotificationRecord
import java.util.concurrent.ConcurrentHashMap

object NotificationMapper {

    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * Ambient status categories. These are displays the user glances at, not events that
     * happened — a step count, a download bar, turn-by-turn directions, a sync spinner.
     * None of them is ever worth an interruption, and all of them update constantly.
     */
    private val AMBIENT_CATEGORIES = setOf(
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_STATUS,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_STOPWATCH,
        Notification.CATEGORY_LOCATION_SHARING,
    )

    /**
     * Things we should never touch.
     *
     * Cancelling a media transport control or a live foreground-service notification does
     * not just annoy the user, it can break the app that posted it — Android reposts
     * foreground-service notifications, and some players lose their controls entirely.
     *
     * Ignored is not the same as hidden. These are left in the shade exactly as the app
     * posted them; Heed simply does not judge, store or re-raise them. A step counter
     * keeps counting where you expect to see it.
     */
    fun isIgnorable(context: Context, sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == context.packageName) return true

        val n = sbn.notification

        // A persistent display rather than an event. FLAG_NO_CLEAR is the giveaway for
        // trackers that keep a notification you cannot swipe away.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (n.flags and Notification.FLAG_NO_CLEAR != 0) return true

        if (n.category in AMBIENT_CATEGORIES) return true

        // Media transport controls.
        val template = n.extras?.getString(Notification.EXTRA_TEMPLATE)
        if (template != null && template.endsWith("MediaStyle")) return true

        // A determinate or indeterminate progress bar means it is still happening; the
        // interesting notification is the one posted when it finishes.
        val extras = n.extras
        if (extras != null &&
            (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false))
        ) return true

        // Nothing to classify.
        val hasText = !extras?.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras?.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
        return !hasText
    }

    /** Stable hash of everything the user would actually read. */
    fun contentHash(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
    ): Int {
        var h = 17
        for (part in listOf(title, text, bigText, subText)) {
            h = h * 31 + (part?.hashCode() ?: 0)
        }
        return h
    }

    fun toRecord(
        context: Context,
        sbn: StatusBarNotification,
        systemImportance: Int,
    ): NotificationRecord {
        val n = sbn.notification
        val e = n.extras

        val title = e?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = e?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = e?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        return NotificationRecord(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            contentHash = contentHash(title, text, bigText, subText),
            category = n.category,
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null,
            systemImportance = systemImportance,
            postedAt = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            hasPerson = hasPerson(sbn),
            conversationId = Conversation.identify(n),
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
