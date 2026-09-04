package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.ScrollDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam: a pause every so many posts, that takes nothing away.
 *
 * Exercised through [ScrollDecision.decide] — the same pure function the accessibility
 * service calls on the hot path — rather than through a copy of the logic. That is the
 * whole reason the decision was pulled out of the service in the first place: the
 * previous version of this file's ancestors tested an `onScroll` that had stopped running
 * months earlier and reported for weeks that behaviour was covered when nothing was.
 */
class ScrollBreakTest {

    private fun rule(
        breakEvents: Int = 30,
        breakSeconds: Int = 20,
        mode: FocusMode = FocusMode.OFF,
        detection: DetectionMode = DetectionMode.PRECISE,
    ) = FocusRule(
        packageName = "com.instagram.android",
        appLabel = "Instagram",
        mode = mode,
        detection = detection,
        scrollBreakEvents = breakEvents,
        breakSeconds = breakSeconds,
    )

    private fun decide(
        rule: FocusRule,
        eventsSinceBreak: Int,
        onFeed: Boolean = true,
        eventsThisBurst: Int = 3,
    ) = ScrollDecision.decide(
        packageName = rule.packageName,
        rule = rule,
        eventsThisBurst = eventsThisBurst,
        cumulativeScrollMs = 0L,
        nudgeThresholdMinutes = 10,
        eventsSinceBreak = eventsSinceBreak,
        onFeed = onFeed,
    )

    @Test
    fun `fires once the post count is reached`() {
        assertEquals(ScrollDecision.Outcome.Continue, decide(rule(), eventsSinceBreak = 29))
        val outcome = decide(rule(), eventsSinceBreak = 30)
        assertTrue("expected a Break, got $outcome", outcome is ScrollDecision.Outcome.Break)
        outcome as ScrollDecision.Outcome.Break
        assertEquals(30, outcome.afterEvents)
        assertEquals(20, outcome.pauseSeconds)
    }

    /**
     * The guard that keeps a pause out of a conversation.
     *
     * In Precise mode the count only accrues on a screen the surface matcher recognises
     * as a feed. Without this, "break the feed" would break a chat — the same failure
     * that made a Block rule throw the user out of a Snapchat conversation mid-sentence,
     * and the one bug in this area that must not come back.
     */
    @Test
    fun `never fires off a feed`() {
        assertEquals(
            ScrollDecision.Outcome.Continue,
            decide(rule(), eventsSinceBreak = 500, onFeed = false),
        )
    }

    /** Automatic mode cannot see a feed, so the caller passes true and the seam applies. */
    @Test
    fun `applies everywhere in automatic mode`() {
        val outcome = decide(
            rule(detection = DetectionMode.BEHAVIOURAL),
            eventsSinceBreak = 40,
            onFeed = true,
        )
        assertTrue(outcome is ScrollDecision.Outcome.Break)
    }

    @Test
    fun `off when not configured`() {
        assertEquals(
            ScrollDecision.Outcome.Continue,
            decide(rule(breakEvents = 0), eventsSinceBreak = 900),
        )
    }

    /**
     * A rule that only breaks the feed still has to be reachable.
     *
     * `decide` returns early for a rule that does nothing, and that guard used to test
     * only the mode and the daily budget — so a rule whose sole setting was a seam was
     * dropped on the first line and could never fire.
     */
    @Test
    fun `a break-only rule is not treated as an empty rule`() {
        val outcome = decide(
            rule(mode = FocusMode.OFF).copy(dailyScrollSeconds = 0),
            eventsSinceBreak = 30,
        )
        assertTrue(outcome is ScrollDecision.Outcome.Break)
    }

    /**
     * Protected apps are protected from this too.
     *
     * A pause is gentler than a block, but an authenticator that stutters while you are
     * reading a code off it is still a focus app getting between you and a login.
     */
    @Test
    fun `never breaks an authenticator`() {
        val protectedRule = rule().copy(
            packageName = "com.google.android.apps.authenticator2",
            appLabel = "Authenticator",
        )
        assertEquals(
            ScrollDecision.Outcome.Continue,
            decide(protectedRule, eventsSinceBreak = 300),
        )
    }

    /** The seam is capped either side, so a bad slider value cannot trap anyone. */
    @Test
    fun `pause length is clamped`() {
        val long = decide(rule(breakSeconds = 9_999), eventsSinceBreak = 30)
        assertEquals(120, (long as ScrollDecision.Outcome.Break).pauseSeconds)
        val short = decide(rule(breakSeconds = 0), eventsSinceBreak = 30)
        assertEquals(3, (short as ScrollDecision.Outcome.Break).pauseSeconds)
    }

    /**
     * A Block rule still wins.
     *
     * Blocking removes you from the screen; a seam hands it back. Firing the seam first
     * would mean waiting out a pause in order to be ejected anyway.
     */
    @Test
    fun `block takes precedence over the seam`() {
        val outcome = decide(
            rule(mode = FocusMode.BLOCK, detection = DetectionMode.BEHAVIOURAL),
            eventsSinceBreak = 30,
            eventsThisBurst = 10,
        )
        assertTrue(outcome is ScrollDecision.Outcome.Stop)
    }
}
