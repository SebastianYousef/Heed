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
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val headline: String, val detail: String) : Verdict
        data class Nudge(val minutes: Int) : Verdict
    }

    /** Checked when an app comes to the foreground, before any scrolling happens. */
    suspend fun onAppOpened(pkg: String): Verdict {
        val rule = data.rule(pkg) ?: return Verdict.Allow
        if (rule.dailyUsageSeconds <= 0) return Verdict.Allow

        val used = data.usageSecondsToday(pkg)
        if (used < rule.dailyUsageSeconds) return Verdict.Allow

        return Verdict.Block(
            headline = "${rule.appLabel} is done for today",
            detail = "You set a limit of ${rule.dailyUsageSeconds / 60} minutes. " +
                "You've used ${used / 60}.",
        )
    }

    /**
     * Checked on every scroll. [eventsThisBurst] is scrolling in the current unbroken
     * stretch, which is what makes BLOCK mode feel instant rather than delayed.
     */
    suspend fun onScroll(pkg: String, eventsThisBurst: Int, burstMs: Long): Verdict {
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

        if (rule.mode == FocusMode.BLOCK && eventsThisBurst >= rule.scrollBudgetEvents) {
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
        fun from(dao: HeedDao) = FocusEnforcer(object : Data {
            override suspend fun rule(pkg: String) = dao.focusRuleFor(pkg)
            override suspend fun scrollSecondsToday(pkg: String) =
                dao.scrollSecondsSince(pkg, startOfToday())
            override suspend fun usageSecondsToday(pkg: String) =
                dao.usageSecondsSince(pkg, startOfToday())
        })

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
