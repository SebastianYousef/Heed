package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusEnforcer
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.ScrollDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SNAP = "com.snapchat.android"
private const val LI = "com.linkedin.android"

/**
 * The scrolling decision, tested against the function the phone actually runs.
 *
 * These tests used to call `FocusEnforcer.onScroll`, which stopped being reachable when
 * the hot path was rewritten to work from a cached rule with no IO. They kept passing for
 * weeks against code that never executed — a worse position than having no tests, because
 * they reported the behaviour was covered. [ScrollDecision] is now the single copy, and
 * this is it.
 */
class ScrollDecisionTest {

    private fun rule(
        mode: FocusMode = FocusMode.OFF,
        detection: DetectionMode = DetectionMode.BEHAVIOURAL,
        budgetEvents: Int = 4,
        dailyScrollSeconds: Int = 0,
    ) = FocusRule(
        packageName = SNAP,
        appLabel = "Snapchat",
        mode = mode,
        detection = detection,
        scrollBudgetEvents = budgetEvents,
        dailyScrollSeconds = dailyScrollSeconds,
    )

    @Test
    fun `an app with no rule of any kind is left alone`() {
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.decide(SNAP, rule(), eventsThisBurst = 500, cumulativeScrollMs = 3_600_000, nudgeThresholdMinutes = 10),
        )
    }

    @Test
    fun `block mode stops within a flick or two`() {
        val r = rule(mode = FocusMode.BLOCK, budgetEvents = 4)
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 3, cumulativeScrollMs = 1_000, nudgeThresholdMinutes = 10),
        )
        assertTrue(
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 4, cumulativeScrollMs = 2_000, nudgeThresholdMinutes = 10)
                is ScrollDecision.Outcome.Stop
        )
    }

    @Test
    fun `precise mode never blocks on a scroll count`() {
        // Fifty scrolls in one burst: a chat history being read back, and exactly the
        // case that used to eject the user mid-conversation. Only surface matching may
        // block in Precise mode.
        val r = rule(mode = FocusMode.BLOCK, detection = DetectionMode.PRECISE, budgetEvents = 4)
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 50, cumulativeScrollMs = 30_000, nudgeThresholdMinutes = 10),
        )
    }

    @Test
    fun `nudging counts scrolling across pauses, not one unbroken burst`() {
        val r = rule(mode = FocusMode.NUDGE)
        // Seven minutes of scrolling in this visit, however many times it was interrupted
        // by actually reading something. The old unbroken-burst rule could not fire at
        // all, which is why LinkedIn appeared to do nothing.
        assertEquals(
            ScrollDecision.Outcome.Nudge(7),
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 3, cumulativeScrollMs = 7 * 60_000L, nudgeThresholdMinutes = 5),
        )
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 200, cumulativeScrollMs = 60_000L, nudgeThresholdMinutes = 5),
        )
    }

    @Test
    fun `a nudge threshold of zero turns nudging off`() {
        val r = rule(mode = FocusMode.NUDGE)
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 200, cumulativeScrollMs = 3_600_000, nudgeThresholdMinutes = 0),
        )
    }

    @Test
    fun `a daily budget is the only outcome that asks for a disk read`() {
        val r = rule(mode = FocusMode.OFF, dailyScrollSeconds = 600)
        assertEquals(
            ScrollDecision.Outcome.NeedsBudgetCheck,
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 1, cumulativeScrollMs = 500, nudgeThresholdMinutes = 10),
        )
        // And the message it produces once the caller has the number.
        val stop = ScrollDecision.budgetExhausted(r)
        assertTrue(stop.headline.contains("Snapchat"))
        assertTrue(stop.detail.contains("10 minutes"))
    }

    @Test
    fun `stopping outranks nudging when a rule could do both`() {
        // A Block rule with a budget set: the immediate stop wins, because the user asked
        // for the feed to end now rather than to be reminded about it.
        val r = rule(mode = FocusMode.BLOCK, budgetEvents = 2, dailyScrollSeconds = 600)
        assertTrue(
            ScrollDecision.decide(SNAP, r, eventsThisBurst = 5, cumulativeScrollMs = 9_000_000, nudgeThresholdMinutes = 1)
                is ScrollDecision.Outcome.Stop
        )
    }
}

/** Entry checks: what happens the moment an app is opened, before a single scroll. */
class FocusEnforcerTest {

    private class Fake(
        private val rule: FocusRule?,
        private val usage: Int = 0,
        private val launches: Int = 0,
        private val bedtime: Boolean = false,
    ) : FocusEnforcer.Data {
        override suspend fun rule(pkg: String) = rule
        override suspend fun usageSecondsToday(pkg: String) = usage
        override suspend fun launchesToday(pkg: String) = launches
        override suspend fun isBedtime() = bedtime
    }

    private val limited = FocusRule(LI, "LinkedIn", dailyUsageSeconds = 1_800, dailyLaunchLimit = 5)

    @Test
    fun `an app with no rule is never stopped`() = runBlocking {
        assertEquals(FocusEnforcer.Verdict.Allow, FocusEnforcer(Fake(null)).onAppOpened(LI))
    }

    @Test
    fun `a time limit closes the app once spent`() = runBlocking {
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(limited, usage = 1_799)).onAppOpened(LI),
        )
        assertTrue(
            FocusEnforcer(Fake(limited, usage = 1_800)).onAppOpened(LI)
                is FocusEnforcer.Verdict.Block
        )
    }

    @Test
    fun `opening it too many times is blocked even under the time limit`() = runBlocking {
        assertTrue(
            FocusEnforcer(Fake(limited, usage = 0, launches = 5)).onAppOpened(LI)
                is FocusEnforcer.Verdict.Block
        )
    }

    @Test
    fun `a critical app is allowed whatever the rule says`() = runBlocking {
        val blocked = FocusRule("com.azure.authenticator", "Authenticator", mode = FocusMode.BLOCK)
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(blocked, bedtime = true)).onAppOpened("com.azure.authenticator"),
        )
    }
}
