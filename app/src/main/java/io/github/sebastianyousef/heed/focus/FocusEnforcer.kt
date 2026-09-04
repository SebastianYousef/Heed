package io.github.sebastianyousef.heed.focus

import io.github.sebastianyousef.heed.data.HeedDao
import io.github.sebastianyousef.heed.core.Time

/**
 * Decides whether an app should be stopped the moment it is opened, and why.
 *
 * Only entry checks live here — limits, launch counts and bedtime — because those are the
 * ones that need a number from the database and can afford to wait for it. Scrolling is
 * decided by [ScrollDecision], which is a pure function because it runs tens of times a
 * second.
 *
 * Split out from the services so the rules can be tested without an accessibility service
 * attached, and so both services can share one answer.
 */
class FocusEnforcer(private val data: Data) {

    /**
     * Narrow view of the database, so the rules can be tested without a Room instance.
     */
    interface Data {
        suspend fun rule(pkg: String): FocusRule?
        suspend fun usageSecondsToday(pkg: String): Int
        suspend fun launchesToday(pkg: String): Int
        suspend fun isBedtime(): Boolean
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val headline: String, val detail: String) : Verdict
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

    companion object {
        fun from(
            dao: HeedDao,
            bedtime: suspend () -> Boolean = { false },
        ) = FocusEnforcer(object : Data {
            override suspend fun rule(pkg: String) = dao.focusRuleFor(pkg)
            override suspend fun usageSecondsToday(pkg: String) =
                dao.usageSecondsSince(pkg, Time.startOfToday())
            override suspend fun launchesToday(pkg: String) =
                dao.launchesSince(pkg, Time.startOfToday())
            override suspend fun isBedtime() = bedtime()
        })

    }
}
