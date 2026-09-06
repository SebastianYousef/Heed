package io.github.sebastianyousef.heed.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import io.github.sebastianyousef.heed.capture.NotificationMapper
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.HeedRepository

/**
 * Turns the system's own usage events into sessions, and works out which notification
 * each session came from.
 *
 * The attribution is the part that matters. A screen-time app can tell you that you spent
 * forty minutes in an app. Only something that also holds your notification history can
 * tell you that a notification you had already marked as noise is what put you there —
 * and only then can that fact be fed back into deciding whether to show you the next one.
 */
class UsageTracker(private val context: Context, private val repo: HeedRepository) {

    /**
     * Ingest everything since the last session we recorded. UsageStatsManager keeps
     * events server-side for days, so this can run on a lazy schedule without losing
     * anything — no need for a service sitting awake watching the foreground app.
     */
    suspend fun ingest(now: Long = System.currentTimeMillis()): Int {
        if (!hasPermission(context)) return 0

        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        val since = (repo.dao.lastSessionEnd() ?: (now - DEFAULT_LOOKBACK_MS)) + 1
        if (since >= now) return 0

        val sessions = buildSessions(manager.queryEvents(since, now), now)
        var created = 0
        for (session in sessions) {
            if (session.packageName == context.packageName) continue
            if (session.durationMs < MIN_SESSION_MS) continue

            val trigger = repo.dao.attributableNotification(
                pkg = session.packageName,
                from = session.startedAt - ATTRIBUTION_WINDOW_MS,
                to = session.startedAt,
            )
            repo.ensurePresetFor(session.packageName, NotificationMapper.appLabel(context, session.packageName))
            repo.dao.insertSession(
                session.copy(
                    appLabel = NotificationMapper.appLabel(context, session.packageName),
                    triggerNotificationId = trigger?.id,
                )
            )
            created++
        }

        attachScrolling()
        trainOnFinishedSessions()
        return created
    }

    /** Pair each session with any scrolling recorded over the same stretch of time. */
    private suspend fun attachScrolling() {
        val spans = repo.dao.unconsumedSpans()
        if (spans.isEmpty()) return
        val sessions = repo.dao.allSessions(500)
        val used = mutableListOf<Long>()

        for (session in sessions) {
            val overlapping = spans.filter {
                it.packageName == session.packageName &&
                    it.startedAt < session.endedAt && it.endedAt > session.startedAt
            }
            if (overlapping.isEmpty()) continue
            repo.dao.attachScrolling(
                id = session.id,
                events = overlapping.sumOf { it.events },
                burst = overlapping.maxOf { it.longestBurstMs },
            )
            used += overlapping.map { it.id }
        }
        if (used.isNotEmpty()) repo.dao.consumeSpans(used.distinct())
    }

    /**
     * The feedback loop. A notification that got tapped and then produced a long scroll
     * was not relevant — it was effective bait, which is the opposite thing.
     */
    private suspend fun trainOnFinishedSessions() {
        for (session in repo.dao.sessionsAwaitingTraining()) {
            val notificationId = session.triggerNotificationId ?: continue
            if (SessionJudge.judge(session) == SessionQuality.SCROLLING) {
                repo.recordFeedback(notificationId, Feedback.CLICKED_THEN_SCROLLED)
            }
            repo.dao.markSessionTrained(session.id)
        }
    }

    private fun buildSessions(events: UsageEvents, now: Long): List<SessionRecord> {
        val open = HashMap<String, Long>()
        val out = mutableListOf<SessionRecord>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = open.remove(pkg) ?: continue
                    out += session(pkg, start, event.timeStamp)
                }
            }
        }
        // Whatever is still in the foreground counts up to now.
        for ((pkg, start) in open) out += session(pkg, start, now)
        return out
    }

    private fun session(pkg: String, start: Long, end: Long) = SessionRecord(
        packageName = pkg,
        appLabel = pkg,
        startedAt = start,
        endedAt = end,
        durationMs = (end - start).coerceAtLeast(0),
    )

    companion object {
        /** How long after a notification a session still counts as caused by it. */
        private const val ATTRIBUTION_WINDOW_MS = 5 * 60 * 1000L

        private const val MIN_SESSION_MS = 3_000L
        private const val DEFAULT_LOOKBACK_MS = 24 * 60 * 60 * 1000L

        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /**
         * Deep-links straight to Heed's own row rather than dumping the user into a list
         * of every app on the phone to hunt through. Falls back to the plain list on
         * devices that ignore the package uri.
         */
        fun settingsIntent(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
}
