package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.FocusEnforcer
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeData(
    private val rule: FocusRule?,
    private val scrollSeconds: Int = 0,
    private val usageSeconds: Int = 0,
) : FocusEnforcer.Data {
    override suspend fun rule(pkg: String) = rule
    override suspend fun scrollSecondsToday(pkg: String) = scrollSeconds
    override suspend fun usageSecondsToday(pkg: String) = usageSeconds
    override suspend fun launchesToday(pkg: String) = 0
    override suspend fun isBedtime() = false
}

private const val SNAP = "com.snapchat.android"
private const val LI = "com.linkedin.android"

class FocusEnforcerTest {

    @Test
    fun `an app with no rule is never touched`() = runBlocking {
        val enforcer = FocusEnforcer(FakeData(null))
        assertEquals(FocusEnforcer.Verdict.Allow, enforcer.onScroll(SNAP, 100, 600_000))
        assertEquals(FocusEnforcer.Verdict.Allow, enforcer.onAppOpened(SNAP))
    }

    @Test
    fun `block mode stops a feed within a flick or two`() = runBlocking {
        val rule = FocusRule(SNAP, "Snapchat", mode = FocusMode.BLOCK, scrollBudgetEvents = 4)
        val enforcer = FocusEnforcer(FakeData(rule))

        // Opening a chat and scrolling a little is left alone.
        assertEquals(FocusEnforcer.Verdict.Allow, enforcer.onScroll(SNAP, 3, 1_000))

        // Continuing past the budget is a feed.
        val verdict = enforcer.onScroll(SNAP, 4, 2_000)
        assertTrue(verdict is FocusEnforcer.Verdict.Block)
    }

    @Test
    fun `a daily scrolling budget leaves the rest of the app alone`() = runBlocking {
        val rule = FocusRule(LI, "LinkedIn", mode = FocusMode.OFF, dailyScrollSeconds = 300)

        // Under budget: nothing happens, even in measure-only mode.
        val under = FocusEnforcer(FakeData(rule, scrollSeconds = 120))
        assertEquals(FocusEnforcer.Verdict.Allow, under.onScroll(LI, 50, 30_000))

        // Over budget: stopped, and the message says the rest of the app still works.
        val over = FocusEnforcer(FakeData(rule, scrollSeconds = 301))
        val verdict = over.onScroll(LI, 1, 500)
        assertTrue(verdict is FocusEnforcer.Verdict.Block)
        assertTrue(
            (verdict as FocusEnforcer.Verdict.Block).detail.contains("Messages"),
        )
    }

    @Test
    fun `a daily time limit is checked on opening, before any scrolling`() = runBlocking {
        val rule = FocusRule(LI, "LinkedIn", dailyUsageSeconds = 1800)

        val under = FocusEnforcer(FakeData(rule, usageSeconds = 1000))
        assertEquals(FocusEnforcer.Verdict.Allow, under.onAppOpened(LI))

        val over = FocusEnforcer(FakeData(rule, usageSeconds = 1900))
        val verdict = over.onAppOpened(LI)
        assertTrue(verdict is FocusEnforcer.Verdict.Block)
        assertTrue((verdict as FocusEnforcer.Verdict.Block).headline.contains("LinkedIn"))
    }

    @Test
    fun `nudge mode reports minutes rather than blocking`() = runBlocking {
        val rule = FocusRule(LI, "LinkedIn", mode = FocusMode.NUDGE)
        val enforcer = FocusEnforcer(FakeData(rule))
        assertEquals(FocusEnforcer.Verdict.Nudge(7), enforcer.onScroll(LI, 200, 7 * 60_000))
        // Under a minute is not yet worth interrupting for.
        assertEquals(FocusEnforcer.Verdict.Allow, enforcer.onScroll(LI, 5, 30_000))
    }

    @Test
    fun `measure-only means measure only`() = runBlocking {
        val rule = FocusRule(SNAP, "Snapchat", mode = FocusMode.OFF)
        val enforcer = FocusEnforcer(FakeData(rule, scrollSeconds = 99_999))
        assertEquals(FocusEnforcer.Verdict.Allow, enforcer.onScroll(SNAP, 500, 3_600_000))
    }
}
