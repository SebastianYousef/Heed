package io.github.sebastianyousef.heed.focus

/**
 * What to do about scrolling right now, decided without touching disk.
 *
 * This exists because the same decision was written twice. [FocusEnforcer] held a
 * `onScroll` that read the rule and the daily total from Room on every scroll event; when
 * that turned out to cost most of Heed's battery, the logic was reimplemented inline in
 * [ScrollWatcherService] against a cached rule — and the original was left behind. Four
 * test classes went on exercising it, which is worse than having no tests at all: they
 * reported that the app behaved correctly while describing a code path that never ran.
 *
 * So the decision lives here, as a pure function of a rule and some counters. The service
 * calls it on the hot path with no coroutine and no IO, and the tests exercise the same
 * function the phone does.
 */
object ScrollDecision {

    sealed interface Outcome {
        /** Nothing to do. Returned for almost every event, so it allocates nothing. */
        data object Continue : Outcome

        /** Leave this screen now. */
        data class Stop(val headline: String, val detail: String) : Outcome

        /** Interrupt gently, having scrolled this many minutes in this visit. */
        data class Nudge(val minutes: Int) : Outcome

        /**
         * A daily budget applies and only the database knows how much is left.
         *
         * Kept as a distinct outcome rather than resolved here so that this function
         * stays pure: the one case that genuinely needs a disk read announces itself,
         * and the caller decides how often it is worth paying for.
         */
        data object NeedsBudgetCheck : Outcome
    }

    /**
     * @param eventsThisBurst scroll events in the current unbroken stretch, which is what
     *        makes Block mode feel immediate rather than delayed.
     * @param cumulativeScrollMs scrolling accumulated across bursts in this visit to the
     *        app. Cumulative rather than unbroken because the unbroken version could not
     *        fire: it asked for ten minutes without a three-second pause, and reading a
     *        single post resets that.
     */
    fun decide(
        packageName: String,
        rule: FocusRule,
        eventsThisBurst: Int,
        cumulativeScrollMs: Long,
        nudgeThresholdMinutes: Int,
    ): Outcome {
        // The guard lives here rather than in the caller so it cannot be left behind by a
        // future rewrite — which is exactly how it nearly was. A focus app that stands
        // between you and a one-time code has stopped being useful and started being a
        // hazard, and no rule the user can set should be able to cause that.
        if (CriticalApps.isProtected(packageName)) return Outcome.Continue
        if (rule.mode == FocusMode.OFF && rule.dailyScrollSeconds <= 0) return Outcome.Continue

        // Behaviour cannot tell a feed from a conversation. Both are TYPE_VIEW_SCROLLED,
        // at the same rate, in the same app — so a scroll-count block in Snapchat threw
        // the user out of a chat with a friend, which is what it did. In Precise mode the
        // decision belongs entirely to surface matching, which knows which screen you are
        // on; this path stays out of it.
        if (rule.mode == FocusMode.BLOCK && rule.detection == DetectionMode.BEHAVIOURAL &&
            eventsThisBurst >= rule.scrollBudgetEvents
        ) {
            return Outcome.Stop(
                headline = "Not this one",
                detail = "You asked Heed to stop you scrolling ${rule.appLabel}.",
            )
        }

        if (rule.mode == FocusMode.NUDGE && nudgeThresholdMinutes > 0) {
            val minutes = (cumulativeScrollMs / 60_000L).toInt()
            if (minutes >= nudgeThresholdMinutes) return Outcome.Nudge(minutes)
        }

        if (rule.dailyScrollSeconds > 0) return Outcome.NeedsBudgetCheck
        return Outcome.Continue
    }

    /** The message for a budget that has run out, once the caller has read the total. */
    fun budgetExhausted(rule: FocusRule) = Outcome.Stop(
        headline = "You're out of scrolling in ${rule.appLabel}",
        detail = "Your budget is ${rule.dailyScrollSeconds / 60} minutes a day. Messages " +
            "and everything else still work — this is just the feed.",
    )
}
