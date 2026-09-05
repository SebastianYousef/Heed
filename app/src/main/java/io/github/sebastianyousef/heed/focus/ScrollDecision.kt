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
         * Put a seam in the feed: cover it, wait, and make continuing a decision.
         *
         * Distinct from [Stop] because nothing is taken away and you are not removed from
         * anything — which is also why it may fire more than once in a visit, where a
         * Stop fires once and is done.
         */
        data class Break(val afterEvents: Int, val pauseSeconds: Int) : Outcome

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
     * @param eventsSinceBreak scroll events counted towards the next seam. Separate from
     *        [eventsThisBurst] because a break is meant to survive the pauses that
     *        reading actually creates — a burst counter resets every time you stop for
     *        three seconds, which in a feed is most of the time.
     * @param onFeed whether the caller knows this scrolling to be happening in a feed.
     *        True in Automatic mode, where nothing better is available; in Precise mode
     *        it is the surface matcher's answer, which is what keeps the seam out of a
     *        conversation.
     */
    fun decide(
        packageName: String,
        rule: FocusRule,
        eventsThisBurst: Int,
        cumulativeScrollMs: Long,
        nudgeThresholdMinutes: Int,
        eventsSinceBreak: Int = 0,
        onFeed: Boolean = true,
    ): Outcome {
        // The guard lives here rather than in the caller so it cannot be left behind by a
        // future rewrite — which is exactly how it nearly was. A focus app that stands
        // between you and a one-time code has stopped being useful and started being a
        // hazard, and no rule the user can set should be able to cause that.
        if (CriticalApps.isProtected(packageName)) return Outcome.Continue
        if (rule.mode == FocusMode.OFF && rule.dailyScrollSeconds <= 0 &&
            rule.scrollBreakEvents <= 0
        ) {
            return Outcome.Continue
        }

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

        // The seam comes before the nudge because it is the cheaper interruption of the
        // two: it costs a pause and hands the feed straight back, where the nudge is a
        // full-screen argument about whether to be here at all. Firing both for the same
        // flick would be two walls in a row.
        //
        // `onFeed` is the guard that keeps this out of a chat. In Precise mode it is the
        // surface matcher's answer and this cannot fire in a conversation; in Automatic
        // it is unconditionally true, because behaviour genuinely cannot tell the two
        // apart — the app says so where the setting is offered rather than pretending
        // to a precision it does not have.
        if (rule.scrollBreakEvents > 0 && onFeed && eventsSinceBreak >= rule.scrollBreakEvents) {
            return Outcome.Break(rule.scrollBreakEvents, rule.breakSeconds.coerceIn(3, 120))
        }

        if (rule.mode == FocusMode.NUDGE && nudgeThresholdMinutes > 0) {
            val minutes = (cumulativeScrollMs / 60_000L).toInt()
            if (minutes >= nudgeThresholdMinutes) return Outcome.Nudge(minutes)
        }

        if (rule.dailyScrollSeconds > 0) return Outcome.NeedsBudgetCheck
        return Outcome.Continue
    }

    /**
     * Whether a group's shared scrolling budget is worth a disk read for this app.
     *
     * Separate from [decide] rather than folded into it, because a group budget applies
     * to members that have no rule at all — and [decide] takes a rule. Making the rule
     * nullable to accommodate that would put a null check on every branch of the one
     * function in Heed that runs tens of times a second, to express something that is
     * genuinely a second, independent question.
     */
    fun groupOutcome(packageName: String, group: AppGroup?): Outcome {
        if (group == null || group.dailyScrollSeconds <= 0) return Outcome.Continue
        if (CriticalApps.isProtected(packageName)) return Outcome.Continue
        return Outcome.NeedsBudgetCheck
    }

    /** The message for a group budget that has run out. */
    fun groupBudgetExhausted(group: AppGroup) = Outcome.Stop(
        headline = "${group.name} is out of scrolling",
        detail = "${group.dailyScrollSeconds / 60} minutes a day across the whole group, " +
            "and it is spent. Switching to another one of them spends the same budget.",
    )

    /** The message for a budget that has run out, once the caller has read the total. */
    fun budgetExhausted(rule: FocusRule) = Outcome.Stop(
        headline = "You're out of scrolling in ${rule.appLabel}",
        detail = "Your budget is ${rule.dailyScrollSeconds / 60} minutes a day. Messages " +
            "and everything else still work — this is just the feed.",
    )
}
