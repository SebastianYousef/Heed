package io.github.sebastianyousef.heed.usage

import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.NotificationRecord

/**
 * What an app actually costs you, joined across both halves of Heed.
 *
 * Screen-time apps can produce [totalMs]. Notification apps can produce [alerts]. Only
 * something holding both can produce [openedFromAlert] and [msFromAlerts] — the chain
 * from "this app interrupted you" to "and that is where the evening went".
 */
data class AttentionStat(
    val packageName: String,
    val appLabel: String,
    /** Notifications from this app that Heed let through. */
    val alerts: Int,
    /** How many of those were followed by you opening the app. */
    val openedFromAlert: Int,
    /** Total foreground time attributable to those notifications. */
    val msFromAlerts: Long,
    /** Total foreground time in this app, however you got there. */
    val totalMs: Long,
    /** Sessions judged to be doom scrolling. */
    val scrollingSessions: Int,
    /** Notifications from this app you explicitly called noise. */
    val markedNoise: Int,
) {
    /** Minutes of your time per notification this app sent. The number that stings. */
    val minutesPerAlert: Double
        get() = if (alerts == 0) 0.0 else (msFromAlerts / 60_000.0) / alerts
}

object AttentionStats {

    fun build(
        notifications: List<NotificationRecord>,
        sessions: List<SessionRecord>,
    ): List<AttentionStat> {
        val byId = notifications.associateBy { it.id }
        val sessionsByPkg = sessions.groupBy { it.packageName }

        val packages = (notifications.map { it.packageName } + sessions.map { it.packageName })
            .distinct()

        return packages.mapNotNull { pkg ->
            val appNotifications = notifications.filter { it.packageName == pkg }
            val appSessions = sessionsByPkg[pkg].orEmpty()
            if (appNotifications.isEmpty() && appSessions.isEmpty()) return@mapNotNull null

            val attributed = appSessions.filter { it.triggerNotificationId?.let(byId::containsKey) == true }

            AttentionStat(
                packageName = pkg,
                appLabel = appNotifications.firstOrNull()?.appLabel
                    ?: appSessions.firstOrNull()?.appLabel ?: pkg,
                alerts = appNotifications.count { it.decision == Decision.ALERTED },
                openedFromAlert = attributed.size,
                msFromAlerts = attributed.sumOf { it.durationMs },
                totalMs = appSessions.sumOf { it.durationMs },
                scrollingSessions = appSessions.count {
                    SessionJudge.judge(it) == SessionQuality.SCROLLING
                },
                markedNoise = appNotifications.count {
                    it.feedback == io.github.sebastianyousef.heed.data.Feedback.MARKED_NOISE ||
                        it.feedback == io.github.sebastianyousef.heed.data.Feedback.CLICKED_THEN_SCROLLED
                },
            )
        }.sortedByDescending { it.msFromAlerts }
    }
}
