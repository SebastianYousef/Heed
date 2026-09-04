package io.github.sebastianyousef.heed.focus

import io.github.sebastianyousef.heed.data.HeedDao
import java.util.Calendar

/**
 * Decides whether an app should be stopped right now, and why.
 *
 * Split out from the accessibility service so the rules can be tested without one, and so
 * the service stays a thin thing that only forwards events.
 */
class FocusEnforcer(private val data: Data) {

    /**
     * Narrow view of the database. Three questions, so the rules can be tested without a
     * Room instance and without an accessibility service.
     */
    interface Data {
        suspend fun rule(pkg: String): FocusRule?
        suspend fun scrollSecondsToday(pkg: String): Int
        suspend fun usageSecondsToday(pkg: String): Int
        suspend fun launchesToday(pkg: String): Int
        suspend fun isBedtime(): Boolean
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val headline: String, val detail: String) : Verdict
        data class Nudge(val minutes: Int) : Verdict
    }

    /** Checked when an app comes to the foreground, before any scrolling happens. */
    suspend fun onAppOpened(pkg: String): Verdict {
        if (CriticalApps.isProtected(pkg)) return Verdict.Allow
        val rule = data.rule(pkg) ?: return Verdict.Allow

        // Bedtime covers every app that has a rule at all, so it needs no separate list.
        // A rule that only asks for grayscale is not a request to be locked out, though.
        if (data.isBedtime() && !rule.onlyChangesColour) {
            return Verdict.Block(
                headline = "It's past your bedtime",
                detail = "${rule.appLabel} is closed until morning. Alarms and calls are " +
                    "untouched.",
            )
        }

        if (rule.dailyLaunchLimit > 0) {
            val launches = data.launchesToday(pkg)
            if (launches >= rule.dailyLaunchLimit) {
                return Verdict.Block(
                    headline = "That's ${launches} opens of ${rule.appLabel} today",
                    detail = "You set a limit of ${rule.dailyLaunchLimit}. Twenty quick " +
                        "checks cost less clock than one long sitting and do more damage.",
                )
            }
        }

        if (rule.dailyUsageSeconds > 0) {
            val used = data.usageSecondsToday(pkg)
            if (used >= rule.dailyUsageSeconds) {
                return Verdict.Block(
                    headline = "${rule.appLabel} is done for today",
                    detail = "You set a limit of ${rule.dailyUsageSeconds / 60} minutes. " +
                        "You've used ${used / 60}.",
                )
            }
        }
        return Verdict.Allow
    }

    /**
     * Checked on every scroll. [eventsThisBurst] is scrolling in the current unbroken
     * stretch, which is what makes BLOCK mode feel instant rather than delayed.
     */
    suspend fun onScroll(pkg: String, eventsThisBurst: Int, burstMs: Long): Verdict {
        if (CriticalApps.isProtected(pkg)) return Verdict.Allow
        val rule = data.rule(pkg) ?: return Verdict.Allow
        if (rule.mode == FocusMode.OFF && rule.dailyScrollSeconds <= 0) return Verdict.Allow

        // A daily scrolling budget: use the app all you like, feed it in moderation.
        if (rule.dailyScrollSeconds > 0) {
            val scrolled = data.scrollSecondsToday(pkg)
            if (scrolled >= rule.dailyScrollSeconds) {
                return Verdict.Block(
                    headline = "You're out of scrolling in ${rule.appLabel}",
                    detail = "Your budget is ${rule.dailyScrollSeconds / 60} minutes a day. " +
                        "Messages and everything else still work — this is just the feed.",
                )
            }
        }

        // Behaviour cannot tell a feed from a conversation. Both are TYPE_VIEW_SCROLLED,
        // at the same rate, in the same app — so a scroll-count block in Snapchat throws
        // you out of a chat with a friend, which is what it did. In Precise mode the
        // decision belongs entirely to surface matching (see ScrollWatcherService), which
        // knows which screen you are on; this path stays out of it.
        if (rule.detection == DetectionMode.BEHAVIOURAL &&
            rule.mode == FocusMode.BLOCK &&
            eventsThisBurst >= rule.scrollBudgetEvents
        ) {
            return Verdict.Block(
                headline = "Not this one",
                detail = "You asked Heed to stop you scrolling ${rule.appLabel}.",
            )
        }

        if (rule.mode == FocusMode.NUDGE) {
            val minutes = (burstMs / 60_000L).toInt()
            if (minutes >= 1) return Verdict.Nudge(minutes)
        }
        return Verdict.Allow
    }

    companion object {
        fun from(
            dao: HeedDao,
            bedtime: suspend () -> Boolean = { false },
        ) = FocusEnforcer(object : Data {
            override suspend fun rule(pkg: String) = dao.focusRuleFor(pkg)
            override suspend fun scrollSecondsToday(pkg: String) =
                dao.scrollSecondsSince(pkg, startOfToday())
            override suspend fun usageSecondsToday(pkg: String) =
                dao.usageSecondsSince(pkg, startOfToday())
            override suspend fun launchesToday(pkg: String) =
                dao.launchesSince(pkg, startOfToday())
            override suspend fun isBedtime() = bedtime()
        })

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
