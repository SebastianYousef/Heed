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

        /** The running focus session, or null. */
        suspend fun focus(): FocusSession.State? = null

        /**
         * Apps a focus session must never turn away: the launcher, and Heed.
         *
         * Blocking sends you to the home screen, so blocking the home screen has nowhere
         * to send you; and blocking Heed hides the button that ends the session. Passed
         * in because which app is the launcher is a fact about the device, not a rule.
         */
        suspend fun focusExempt(): Set<String> = emptySet()
    }

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val headline: String, val detail: String) : Verdict
    }

    /** Checked when an app comes to the foreground, before any scrolling happens. */
    suspend fun onAppOpened(pkg: String): Verdict {
        if (CriticalApps.isProtected(pkg)) return Verdict.Allow

        // Checked before the rule lookup, and this is the ordering that matters. Every
        // other limit here is per-app and answers "have you had enough of this one"; a
        // focus session is the opposite question — everything is closed unless you named
        // it — so it has to apply to apps that have no rule at all, which is nearly all
        // of them.
        val focus = data.focus()
        if (FocusSession.blocks(focus, pkg, data.focusExempt(), System.currentTimeMillis())) {
            val remaining = focus!!.remainingMs(System.currentTimeMillis())
            return Verdict.Block(
                headline = "You're in a ${focus.label.lowercase()} session",
                detail = if (remaining != null) {
                    "${Time.duration(remaining)} left. Ending it early takes " +
                        "${FocusSession.END_DELAY_SECONDS} seconds, from inside Heed."
                } else {
                    "Running since ${Time.duration(focus.elapsedMs(System.currentTimeMillis()))} " +
                        "ago. End it from inside Heed when you are done."
                },
            )
        }

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
            focus: suspend () -> FocusSession.State? = { null },
            exempt: suspend () -> Set<String> = { emptySet() },
        ) = FocusEnforcer(object : Data {
            override suspend fun rule(pkg: String) = dao.focusRuleFor(pkg)
            override suspend fun usageSecondsToday(pkg: String) =
                dao.usageSecondsSince(pkg, Time.startOfToday())
            override suspend fun launchesToday(pkg: String) =
                dao.launchesSince(pkg, Time.startOfToday())
            override suspend fun isBedtime() = bedtime()
            override suspend fun focus() = focus()
            override suspend fun focusExempt() = exempt()
        })

    }
}
